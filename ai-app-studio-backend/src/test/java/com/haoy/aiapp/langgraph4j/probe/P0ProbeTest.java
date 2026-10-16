package com.haoy.aiapp.langgraph4j.probe;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.Serializable;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 第 10 节 P0 探针 —— 只验证「方案里不确定的点」，不碰任何业务代码。
 * <p>
 * 四个探针分别对应方案文档里的四处风险：
 * <ol>
 *   <li>{@link #probe1_separateModelToolCall()} —— 不带 JSON 约束的独立模型能否正常做工具调用</li>
 *   <li>{@link #probe2_sharedChatModelToolCall()} —— 共享 chatModel（带 json_object）做工具调用会怎样</li>
 *   <li>{@link #probe3_structuredNestedRecord()} —— 结构化输出返回带嵌套 record 的 POJO 能否成功</li>
 *   <li>{@link #probe4_undrawDynamicBuildId()} —— unDraw 动态 buildId 方案在 Java 侧是否可行</li>
 * </ol>
 * 本类属于「一次性探针」，P0 验证结束后可以删除，也可以留着当回归用。
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
@SpringBootTest
class P0ProbeTest {

    @Value("${langchain4j.open-ai.chat-model.base-url}")
    private String baseUrl;

    @Value("${langchain4j.open-ai.chat-model.api-key}")
    private String apiKey;

    @Value("${langchain4j.open-ai.chat-model.model-name}")
    private String modelName;

    @Value("${pexels.api-key:}")
    private String pexelsApiKey;

    /**
     * 共享的 chat-model Bean —— 全局配置里开了 strict-json-schema + response-format: json_object
     */
    @Resource
    private ChatModel chatModel;

    // ==================== 探针用的小工具集 ====================

    /**
     * 探针工具：模拟「图片搜索工具」这类会被 AI 自主调用的工具
     */
    public static class ProbeTools {

        @Tool("查询指定城市当前的天气情况")
        public String getWeather(@P("城市名称，例如：北京") String city) {
            log.info("[TOOL] getWeather 被调用了，入参 city = {}", city);
            return city + " 当前天气：晴，气温 25 摄氏度。";
        }
    }

    public interface ProbeAssistant {
        @SystemMessage("你是天气助手。回答用户问题前必须先调用 getWeather 工具获取真实数据，不要凭记忆回答。")
        String ask(@UserMessage String question);
    }

    /**
     * 对照用：系统提示词里**显式带上 "json" 字样** —— 用来排除
     * 「PROBE2 失败只是因为提示词里没有 json」这一干扰因素，
     * 从而判断「工具调用 + response_format=json_object」到底能不能共存。
     */
    public interface ProbeAssistantWithJsonHint {
        @SystemMessage("你是天气助手。回答用户问题前必须先调用 getWeather 工具获取真实数据，不要凭记忆回答。你必须按照 JSON 格式输出！")
        String ask(@UserMessage String question);
    }

    /** 模拟 ImageCollectionPlan 里的嵌套 record 结构 */
    public record SearchTask(String query) implements Serializable {
    }

    public record DiagramTask(String mermaidCode, String description) implements Serializable {
    }

    public record ProbePlan(List<SearchTask> contentImageTasks,
                            List<DiagramTask> diagramTasks) implements Serializable {
    }

    public interface ProbePlanner {
        @SystemMessage("""
                你是一个专业的图片收集规划师。请分析用户的网站需求，制定图片收集计划。
                请严格按照以下 JSON 格式返回：
                json
                {"contentImageTasks":[{"query":"搜索关键词"}],"diagramTasks":[{"mermaidCode":"mermaid代码","description":"图表用途"}]}
                """)
        ProbePlan plan(@UserMessage String userPrompt);
    }

    // ==================== 探针 1：独立模型 + 工具调用 ====================

    /**
     * 验证「沿用 AiCodeGenTypeRoutingServiceFactory 的写法，自己造一个不带 JSON 约束的模型」
     * 能否正常完成工具调用 —— 这是 ImageCollectionService 的落地前提。
     */
    @Test
    void probe1_separateModelToolCall() {
        ChatModel dedicatedModel = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .logRequests(true)
                .logResponses(true)
                .build();

        ProbeAssistant assistant = AiServices.builder(ProbeAssistant.class)
                .chatModel(dedicatedModel)
                .tools(new ProbeTools())
                .build();

        try {
            String answer = assistant.ask("北京天气怎么样？");
            log.info("PROBE1_RESULT=SUCCESS 回答={}", answer);
        } catch (Exception e) {
            log.error("PROBE1_RESULT=FAIL {}: {}", e.getClass().getSimpleName(), e.getMessage(), e);
        }
    }

    // ==================== 探针 2：共享 chatModel + 工具调用 ====================

    /**
     * 验证「直接复用共享 ChatModel」在本项目是否可行。
     * <p>
     * 共享 chat-model 带了 {@code response-format: json_object} + {@code strict-json-schema}，
     * 再叠加 tools 属于未定义组合，预期会失败 —— 如果这里真的失败了，
     * 就坐实了方案 §2.3「必须单独建模型」的结论。
     */
    @Test
    void probe2_sharedChatModelToolCall() {
        ProbeAssistant assistant = AiServices.builder(ProbeAssistant.class)
                .chatModel(chatModel)
                .tools(new ProbeTools())
                .build();

        try {
            String answer = assistant.ask("北京的天气如何？");
            log.warn("PROBE2_RESULT=UNEXPECTED_SUCCESS 共享模型也能做工具调用，回答={}", answer);
        } catch (Exception e) {
            log.warn("PROBE2_RESULT=FAIL_AS_EXPECTED {}: {}", e.getClass().getSimpleName(), e.getMessage());
        }
    }

    // ==================== 探针 2b：共享 chatModel + 工具调用（带 json 提示词对照） ====================

    /**
     * 对照实验：把「PROBE2 失败是否只因为提示词没有 json」这个干扰因素排除掉。
     * <p>
     * 本项目的 {@code image-collection-system-prompt.txt} 末尾恰好写了「你必须按照 JSON 格式输出！」，
     * 所以必须用带 json 字样的提示词再测一次，才能判断：
     * <ul>
     *   <li>若<b>仍然失败</b> → 「工具调用 + json_object」确实不兼容，方案 §2.3 结论成立；</li>
     *   <li>若<b>成功</b> → 直接复用共享模型的写法在本项目能跑，但模型会被推向「输出 JSON」而不是「调用工具」，
     *       属于「能跑但脆弱」，仍然建议用独立模型。</li>
     * </ul>
     */
    @Test
    void probe2b_sharedChatModelToolCallWithJsonHint() {
        ProbeAssistantWithJsonHint assistant = AiServices.builder(ProbeAssistantWithJsonHint.class)
                .chatModel(chatModel)
                .tools(new ProbeTools())
                .build();

        try {
            String answer = assistant.ask("上海的天气如何？");
            log.warn("PROBE2B_RESULT=SUCCESS 共享模型 + json 提示词也能做工具调用，回答={}", answer);
        } catch (Exception e) {
            log.warn("PROBE2B_RESULT=FAIL {}: {}", e.getClass().getSimpleName(), e.getMessage());
        }
    }

    // ==================== 探针 3：结构化输出 + 嵌套 record ====================

    /**
     * 验证共享 chatModel 能否把「带嵌套 record 的 POJO」作为结构化输出返回 ——
     * 这是 ImageCollectionPlanService / CodeQualityCheckService 的落地前提。
     */
    @Test
    void probe3_structuredNestedRecord() {
        ProbePlanner planner = AiServices.builder(ProbePlanner.class)
                .chatModel(chatModel)
                .build();

        try {
            ProbePlan plan = planner.plan("创建一个技术博客网站，需要展示编程教程和系统架构");
            if (plan == null) {
                log.error("PROBE3_RESULT=FAIL 返回了 null");
                return;
            }
            log.info("PROBE3_RESULT=SUCCESS 内容图片任务={} 条，架构图任务={} 条",
                    plan.contentImageTasks() == null ? "null" : plan.contentImageTasks().size(),
                    plan.diagramTasks() == null ? "null" : plan.diagramTasks().size());
        } catch (Exception e) {
            log.error("PROBE3_RESULT=FAIL {}: {}", e.getClass().getSimpleName(), e.getMessage(), e);
        }
    }

    // ==================== 探针 4：unDraw 动态 buildId ====================

    /**
     * 验证「先取 buildId 再拼数据地址」在 Java + Hutool 侧同样可行。
     * <p>
     * 写死的 {@code mMWmJSt23qpgo8cLTD_pB} 实测已 404，必须动态取。
     */
    @Test
    void probe4_undrawDynamicBuildId() {
        String buildId;
        try (HttpResponse page = HttpRequest.get("https://undraw.co/illustrations")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120 Safari/537.36")
                .timeout(30000)
                .execute()) {
            if (!page.isOk()) {
                log.error("PROBE4_RESULT=FAIL 首页返回 {}", page.getStatus());
                return;
            }
            Matcher m = Pattern.compile("\"buildId\":\"([^\"]+)\"").matcher(page.body());
            if (!m.find()) {
                log.error("PROBE4_RESULT=FAIL 首页里没找到 buildId");
                return;
            }
            buildId = m.group(1);
        } catch (Exception e) {
            log.error("PROBE4_RESULT=FAIL 取 buildId 异常 {}: {}", e.getClass().getSimpleName(), e.getMessage());
            return;
        }
        log.info("PROBE4 buildId = {}", buildId);

        String apiUrl = String.format(
                "https://undraw.co/_next/data/%s/search/%s.json?term=%s", buildId, "happy", "happy");
        try (HttpResponse response = HttpRequest.get(apiUrl).timeout(30000).execute()) {
            if (!response.isOk()) {
                log.error("PROBE4_RESULT=FAIL 数据接口返回 {}", response.getStatus());
                return;
            }
            JSONObject pageProps = JSONUtil.parseObj(response.body()).getJSONObject("pageProps");
            JSONArray results = pageProps == null ? null : pageProps.getJSONArray("initialResults");
            if (results == null || results.isEmpty()) {
                log.error("PROBE4_RESULT=FAIL initialResults 为空");
                return;
            }
            JSONObject first = results.getJSONObject(0);
            log.info("PROBE4_RESULT=SUCCESS 拿到 {} 条插画，示例：title={} media={}",
                    results.size(), first.getStr("title"), first.getStr("media"));
        } catch (Exception e) {
            log.error("PROBE4_RESULT=FAIL 取插画异常 {}: {}", e.getClass().getSimpleName(), e.getMessage());
        }
    }

    // ==================== 探针 5：Pexels（需要配置 key） ====================

    /**
     * 验证 Pexels 图片搜索。未配置 {@code pexels.api-key} 时会跳过并给出提示。
     */
    @Test
    void probe5_pexelsSearch() {
        if (StrUtil.isBlank(pexelsApiKey)) {
            log.warn("PROBE5_RESULT=SKIPPED 未配置 pexels.api-key，请在 application-local.yml 里补上后再跑");
            return;
        }
        try (HttpResponse response = HttpRequest.get("https://api.pexels.com/v1/search")
                .header("Authorization", pexelsApiKey)
                .form("query", "technology")
                .form("per_page", 3)
                .form("page", 1)
                .timeout(30000)
                .execute()) {
            if (!response.isOk()) {
                log.error("PROBE5_RESULT=FAIL 状态码={} body={}", response.getStatus(), response.body());
                return;
            }
            JSONArray photos = JSONUtil.parseObj(response.body()).getJSONArray("photos");
            if (photos == null || photos.isEmpty()) {
                log.error("PROBE5_RESULT=FAIL photos 为空");
                return;
            }
            JSONObject first = photos.getJSONObject(0);
            log.info("PROBE5_RESULT=SUCCESS 搜到 {} 张，示例：alt={} url={}",
                    photos.size(), first.getStr("alt"), first.getJSONObject("src").getStr("medium"));
        } catch (Exception e) {
            log.error("PROBE5_RESULT=FAIL {}: {}", e.getClass().getSimpleName(), e.getMessage());
        }
    }
}
