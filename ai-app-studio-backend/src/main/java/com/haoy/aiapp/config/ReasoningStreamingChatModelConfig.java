package com.haoy.aiapp.config;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

/**
 * 推理流式模型配置（复杂工程代码生成用，带工具调用能力）
 * <p>
 * 用于 Vue 等复杂项目的代码生成，需要更强的推理能力。
 * <p>
 * 配置前缀复用已有的 {@code langchain4j.open-ai.chat-model}，
 * 因为这里只需要 baseUrl / apiKey 两个字段，复用可以避免密钥在 yml 里维护两份。
 * 注意只读取这两个字段，其它字段（strict-json-schema / response-format）不会被带进来，
 * 流式模型绝对不能带 response-format（会让 DeepSeek 报 "Prompt must contain the word 'json'"）。
 * <p>
 * ⚠️ 第 11 节改造：Bean 名称由方法名决定，已从 {@code reasoningStreamingChatModel} 改为
 * {@code reasoningStreamingChatModelPrototype}，并加上 {@code @Scope("prototype")}。
 * 原先是单例 —— 底层 HTTP 客户端被所有请求共用，而流式解析是同步的，
 * 结果就是「10 个人同时生成，只有第 1 个在真正跑」。改多例后每次 {@code getObject()}
 * 都是一个全新实例，才能真并发。注入方（{@code AiCodeGeneratorServiceFactory}）同步改成了
 * 按这个新名字 {@code @Qualifier} 注入 {@code ObjectProvider}。
 *
 * @author <a>程序员haoy</a>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "langchain4j.open-ai.chat-model")
public class ReasoningStreamingChatModelConfig {

    /** API 地址 */
    private String baseUrl;

    /** API 密钥 */
    private String apiKey;

    /**
     * 推理流式模型（用于复杂项目生成，如 Vue，带工具调用）
     * <p>
     * ⚠️ {@code prototype} 是本方法的核心：它是「多例」真正生效的地方。
     * 调用方必须通过 {@code ObjectProvider#getObject()} 取新实例，
     * 直接 {@code @Resource} 注入只会拿到启动时创建的那一个，等于没改。
     */
    @Bean("reasoningStreamingChatModelPrototype")
    @Scope("prototype")
    public StreamingChatModel reasoningStreamingChatModelPrototype() {
        // TODO: 测试环境暂时用 deepseek-chat + 32768 tokens，生产环境切回下面两行
        final String modelName = "deepseek-chat";
        // Vue 工程模式是「一次并行写多个文件」，8192 容易把响应截断在半路（表现为生成到一半停了）
        // 32768 只是上限、不会因此多花钱，且与 yml 里 chat-model / streaming-chat-model 保持一致
        final int maxTokens = 32768;
        // 生产环境：
        // final String modelName = "deepseek-reasoner";
        // final int maxTokens = 32768;

        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .maxTokens(maxTokens)
                .logRequests(true)
                .logResponses(true)
                .build();
    }
}