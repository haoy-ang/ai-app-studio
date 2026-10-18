package com.haoy.aiapp.ai;

import cn.hutool.core.util.StrUtil;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * AI 代码生成类型路由服务工厂
 * <p>
 * 路由只做「HTML / 多文件 / Vue 三选一」的分类，输出最多几个 token，
 * 所以第 11 节把它从 deepseek-chat 换成阿里云百炼的 <b>qwen-turbo</b>（便宜 10 倍以上、更快）。
 * 走百炼的 <b>OpenAI 兼容层</b>（{@code /compatible-mode/v1}），不额外引 dashscope 专用 starter，
 * 依赖面最小。
 * <p>
 * ⚠️ 为什么<b>不</b>把路由模型注册成 Spring Bean：
 * 项目里有 4 处 {@code @Resource private ChatModel chatModel;} 是按<b>类型</b>注入的
 * （{@code AiCodeGeneratorServiceFactory} 和 langgraph4j 下的三个 ServiceFactory）。
 * 容器里一旦出现第二个 {@code ChatModel}，这 4 处立刻 {@code NoUniqueBeanDefinitionException}、
 * 应用直接起不来。所以这里改成「每次调用时在方法里 new」，效果与 prototype 等价，
 * 但那个副作用不存在。
 * <p>
 * ⚠️ 另外，路由返回的是<b>枚举</b>，而全局 chat-model 开了 {@code response-format: json_object}，
 * DeepSeek 要求 json_object 模式的提示词里必须出现 "json" 字样，路由提示词没有，会被 400 拒绝 ——
 * 所以必须用这个独立的、不带 JSON 约束的模型，不能复用全局 chatModel。
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
@Configuration
public class AiCodeGenTypeRoutingServiceFactory {

    /**
     * 要求上游返回不压缩的响应体
     * <p>
     * 这不是「优化」，是绕坑：Spring {@code RestClient} 自动选中的 Apache HttpClient 5
     * 在解压这类 chunked 响应时会截断（实测 body 只剩 {@code "{"}）。
     * 详见下面 {@code buildService} 里的注释。
     */
    private static final Map<String, String> ACCEPT_IDENTITY = Map.of("Accept-Encoding", "identity");

    /**
     * 路由专用模型（阿里云百炼 OpenAI 兼容层）的连接信息
     */
    @Value("${langchain4j.open-ai.routing-chat-model.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String baseUrl;

    /** 阿里云百炼 API Key（用户自行填入 application-local.yml）。留空则走下面的降级逻辑 */
    @Value("${langchain4j.open-ai.routing-chat-model.api-key:}")
    private String apiKey;

    @Value("${langchain4j.open-ai.routing-chat-model.model-name:qwen-turbo}")
    private String modelName;

    /** 分类任务输出极短，卡在 100 防止意外长输出 */
    @Value("${langchain4j.open-ai.routing-chat-model.max-tokens:100}")
    private Integer maxTokens;

    /**
     * 调用失败的重试次数
     * <p>
     * <b>这个值只能兜住瞬时抖动，别指望它解决限流。</b>百炼返回的 429 是
     * {@code limit_requests}（每分钟请求数超限），重试 3 次如果还落在同一分钟内，照样全失败。
     * 真正要控的是「调用频率」—— 见 {@link #createAiCodeGenTypeRoutingService()} 上方的说明。
     */
    @Value("${langchain4j.open-ai.routing-chat-model.max-retries:3}")
    private Integer maxRetries;

    /**
     * 降级用的连接信息（全局 chat-model，即 DeepSeek）。
     * <p>
     * 为什么需要降级：百炼 Key 是「后填」的，如果在没填 Key 时就因为
     * {@code OpenAiChatModel.builder().apiKey("")} 抛异常，应用根本起不来，
     * 连接口文档打不开、其它功能也全废。空 Key 时退回原来的 deepseek-chat，
     * 行为与改造前一致；填好 Key 后自动切到 qwen-turbo，无需改代码。
     */
    @Value("${langchain4j.open-ai.chat-model.base-url}")
    private String fallbackBaseUrl;

    @Value("${langchain4j.open-ai.chat-model.api-key}")
    private String fallbackApiKey;

    @Value("${langchain4j.open-ai.chat-model.model-name}")
    private String fallbackModelName;

    /**
     * 创建 AI 代码生成类型路由服务实例
     * <p>
     * 每次调用都新建一个（内含全新的 ChatModel），支持并发 —— 多个请求同时创建应用时
     * 不会排队抢同一个模型实例。用非流式模型：路由要的是「一次性返回一个枚举」。
     *
     * @return 全新的路由服务实例
     */
    public AiCodeGenTypeRoutingService createAiCodeGenTypeRoutingService() {
        String useBaseUrl = baseUrl;
        String useApiKey = apiKey;
        String useModelName = modelName;
        if (StrUtil.isBlank(useApiKey)) {
            log.warn("未配置 langchain4j.open-ai.routing-chat-model.api-key（阿里云百炼），"
                            + "智能路由暂时降级为 {}/{}；填好 Key 后会自动切换到 {}/{}",
                    fallbackBaseUrl, fallbackModelName, baseUrl, modelName);
            useBaseUrl = fallbackBaseUrl;
            useApiKey = fallbackApiKey;
            useModelName = fallbackModelName;
        }
        return buildService(useBaseUrl, useApiKey, useModelName);
    }

    /**
     * 创建<b>备用</b>路由服务：固定用主链路的模型（{@code langchain4j.open-ai.chat-model}）
     * <p>
     * <b>它什么时候派上用场：</b>主路由模型（百炼 qwen-turbo）返回 429 / 超时的时候。
     * 那种情况下模型<b>根本没机会判断</b>，直接退回 HTML 会让用户拿到错误形态的产物 ——
     * 换成主链路模型再判一次，多数时候能救回来（实测百炼限流时 deepseek 是好的）。
     * <p>
     * <b>为什么不复用全局 {@code ChatModel} Bean：</b>那个 Bean 开了
     * {@code response-format: json_object} + {@code strict-json-schema}，
     * 而路由的返回类型是枚举、提示词里也没有 "json" 字样，DeepSeek 会直接 400。
     * 所以这里必须新建一个「不带 JSON 约束」的模型实例。
     * <p>
     * 代价说明：每次启动多建一个模型客户端（纯内存对象，不联网），每次降级多一次调用。
     * 相对于「产出错误类型的应用」，这个代价可以忽略。
     *
     * @return 备用路由服务实例
     */
    public AiCodeGenTypeRoutingService createFallbackRoutingService() {
        return buildService(fallbackBaseUrl, fallbackApiKey, fallbackModelName);
    }

    /**
     * 按给定连接信息造一个路由服务
     * <p>
     * 单独构建一个不带 JSON 约束的轻量模型，只用于路由。
     */
    private AiCodeGenTypeRoutingService buildService(String useBaseUrl, String useApiKey, String useModelName) {
        ChatModel routingChatModel = OpenAiChatModel.builder()
                .baseUrl(useBaseUrl)
                .apiKey(useApiKey)
                .modelName(useModelName)
                .maxTokens(maxTokens)
                .maxRetries(maxRetries)
                .logRequests(true)
                .logResponses(true)
                // ⚠️ 必须带这个头，否则路由必然 JsonEOFException（实测 2026-10-18）。
                // 链路是 langchain4j SpringRestClient → Spring RestClient → 自动选中 Apache HttpClient 5
                // （classpath 上被 webdrivermanager 拖进来了），它在这条 CDN 的
                // 「vary: accept-encoding + Transfer-Encoding: chunked」响应上解压会截断，
                // 只读到第一个字节 "{"。同一个请求：裸 HC5 → 454 字节，Spring+HC5 → 1 字节，
                // Spring+HC5 加 identity → 454 字节。所以不是客户端选错，是别让上游压缩。
                // 全局模型（starter 的 chat-model / 流式）在 application.yml 的 custom-headers 里配同一项。
                .customHeaders(ACCEPT_IDENTITY)
                .build();
        return AiServices.builder(AiCodeGenTypeRoutingService.class)
                .chatModel(routingChatModel)
                .build();
    }

    /**
     * 默认 Bean：工作流的 {@code RouterNode} 依赖它。
     * <p>
     * ⚠️ 只给「无状态、不需要并发隔离」的场景用。聊天链路（{@code AppServiceImpl}）
     * 每次创建应用都调 {@link #createAiCodeGenTypeRoutingService()} 拿新实例，避免并发排队。
     */
    @Bean
    public AiCodeGenTypeRoutingService aiCodeGenTypeRoutingService() {
        return createAiCodeGenTypeRoutingService();
    }
}
