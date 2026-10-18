package com.haoy.aiapp.ai;

import cn.hutool.core.util.StrUtil;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
        // 单独构建一个不带 JSON 约束的轻量模型，只用于路由
        ChatModel routingChatModel = OpenAiChatModel.builder()
                .baseUrl(useBaseUrl)
                .apiKey(useApiKey)
                .modelName(useModelName)
                .maxTokens(maxTokens)
                .maxRetries(maxRetries)
                .logRequests(true)
                .logResponses(true)
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
