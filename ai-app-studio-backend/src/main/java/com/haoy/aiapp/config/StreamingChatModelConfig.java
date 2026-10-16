package com.haoy.aiapp.config;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

/**
 * 通用流式模型配置（HTML / 多文件用）
 * <p>
 * 它和 {@link ReasoningStreamingChatModelConfig} 是一对：那个给 Vue 用，这个给 HTML / 多文件用。
 * 两者都有一个 {@code @Scope("prototype")} 的 Bean，供 {@code AiCodeGeneratorServiceFactory}
 * 通过 {@code ObjectProvider} 按需取新实例。
 * <p>
 * ⚠️ 为什么要多例：langchain4j 的 starter 默认把流式模型注册成<b>单例</b>
 * （Bean 名 {@code openAiStreamingChatModel}），所有请求共用同一份底层 HTTP 客户端，
 * 而它的流式解析是同步阻塞的 —— 于是「10 个人同时生成代码，只有第 1 个人的流在动」。
 * 多例之后每个请求拿到独立实例，才能真正并发。
 * <p>
 * ⚠️ 注意本类只读 {@code temperature}（以及必须的连接信息），刻意<b>不</b>读
 * {@code log-requests / log-responses}：日志开关留给 starter 那个单例，避免两边重复打印。
 *
 * @author <a>程序员haoy</a>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "langchain4j.open-ai.streaming-chat-model")
public class StreamingChatModelConfig {

    /** API 地址 */
    private String baseUrl;

    /** API 密钥 */
    private String apiKey;

    /** 模型名（本项目固定 deepseek-chat） */
    private String modelName;

    /** 最大输出 token 数 */
    private Integer maxTokens;

    /** 采样温度。yml 里不填会是 null，构建时会被忽略（用模型默认值） */
    private Double temperature;

    /**
     * 通用流式模型（多例）
     * <p>
     * ⚠️ 调用方必须用 {@code ObjectProvider#getObject()} 取，每次都是新实例。
     * 直接注入单例的话，多例就形同虚设。
     */
    @Bean("streamingChatModelPrototype")
    @Scope("prototype")
    public StreamingChatModel streamingChatModelPrototype() {
        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .maxTokens(maxTokens)
                .temperature(temperature)
                .build();
    }
}
