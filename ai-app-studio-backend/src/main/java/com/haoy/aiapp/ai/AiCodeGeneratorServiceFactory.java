package com.haoy.aiapp.ai;

import com.haoy.aiapp.ai.guardrail.PromptSafetyInputGuardrail;
import com.haoy.aiapp.ai.tools.ToolManager;
import com.haoy.aiapp.exception.BusinessException;
import com.haoy.aiapp.exception.ErrorCode;
import com.haoy.aiapp.model.enums.CodeGenTypeEnum;
import com.haoy.aiapp.service.chathistory.ChatHistoryService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * AI 代码生成服务工厂
 * <p>
 * 根据 appId + CodeGenTypeEnum 创建独立的 AiCodeGeneratorService 实例，
 * 每个组合拥有独立的 Redis 聊天记忆窗口（最多保留 20 条历史），
 * 实例通过 Caffeine 本地缓存复用，避免重复构建。
 */
@Slf4j
@Configuration
public class AiCodeGeneratorServiceFactory {

    @Resource
    private ChatModel chatModel;

    @Resource
    private RedisChatMemoryStore redisChatMemoryStore;

    @Resource
    private ChatHistoryService chatHistoryService;

    /**
     * 工具管理器：一次性收齐所有文件工具（写 / 读 / 读目录 / 改 / 删）
     * <p>
     * 这里注入的是管理器而不是某一个具体工具，所以以后新增工具
     * 只需要写一个 {@code @Component} 工具类，工厂和流处理都不用动。
     */
    @Resource
    private ToolManager toolManager;

    /**
     * Prompt 安全输入护轨
     * <p>
     * 两个分支都要挂：MULTI_FILE / HTML 同样能被注入攻击，若只在 Vue 分支挂护轨属于疏漏。
     * 注入的是 Bean（不是 {@code new}），这样敏感词配置才能从 yml 读、改配置不用重编译。
     */
    @Resource
    private PromptSafetyInputGuardrail promptSafetyInputGuardrail;

    /**
     * 通用流式模型提供者（HTML / 多文件）
     * <p>
     * ⚠️ 必须是 {@link ObjectProvider} 而<b>不是</b> {@code StreamingChatModel}：
     * 对应 Bean 是 {@code @Scope("prototype")} 的，用 {@code @Resource} 直接注入
     * 只会在启动时取一次实例并永久复用，多例就白改了。
     * {@code getObject()} 每次调用才真正新建一个实例。
     */
    private final ObjectProvider<StreamingChatModel> htmlStreamingModelProvider;

    /**
     * 推理流式模型提供者（Vue 工程模式）
     */
    private final ObjectProvider<StreamingChatModel> reasoningStreamingModelProvider;

    /**
     * 构造注入两个流的「模型工厂」。
     * <p>
     * 用 {@code @Qualifier} 指定 Bean 名 —— 容器里流式模型有好几个（starter 的单例
     * {@code openAiStreamingChatModel} + 我们这两个 prototype），不指定名字会
     * {@code NoUniqueBeanDefinitionException}。
     */
    public AiCodeGeneratorServiceFactory(
            @Qualifier("streamingChatModelPrototype")
            ObjectProvider<StreamingChatModel> htmlStreamingModelProvider,
            @Qualifier("reasoningStreamingChatModelPrototype")
            ObjectProvider<StreamingChatModel> reasoningStreamingModelProvider) {
        this.htmlStreamingModelProvider = htmlStreamingModelProvider;
        this.reasoningStreamingModelProvider = reasoningStreamingModelProvider;
    }

    /**
     * 普通对话（HTML / 多文件）的聊天记忆窗口大小。
     * <p>
     * 这两类模式一轮问答只产生 2~3 条消息，20 条足够覆盖「生成 + 修改」的全过程。
     */
    private static final int DEFAULT_MEMORY_WINDOW_SIZE = 20;

    /**
     * Vue 工程模式的聊天记忆窗口大小。
     * <p>
     * ⚠️ Vue 走的是「工具调用」增量生成：模型每写一个文件就产生 2 条消息
     * （AI 的 tool_call + 工具执行结果），一个 20~30 个文件的工程轻松超过 60 条。
     * 如果窗口按 20 条配，开头的「用户需求」会被挤出去，模型既不知道自己要建什么、
     * 也记不全已经写过哪些文件，就会一轮一轮地重复写同一批文件（实测连续 19 分钟
     * 写了 196 次、永不停止，SSE 一直不结束，npm 构建永远不触发）。
     * 所以这里必须给足窗口，让它能看见完整的生成过程。
     */
    private static final int VUE_MEMORY_WINDOW_SIZE = 100;

    /**
     * AI 服务实例本地缓存
     * <p>
     * Key 格式：{appId}_{codeGenType}，确保同一 appId 不同生成类型各自独立缓存。
     * <ul>
     *   <li>最大缓存 1000 个实例</li>
     *   <li>写入后 30 分钟自动过期</li>
     *   <li>访问后 10 分钟未访问自动过期</li>
     * </ul>
     */
    private final Cache<String, AiCodeGeneratorService> serviceCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(Duration.ofMinutes(30))
            .expireAfterAccess(Duration.ofMinutes(10))
            .removalListener((key, value, cause) ->
                    log.debug("AI 服务实例被移除, 缓存键: {}, 原因: {}", key, cause))
            .build();

    /**
     * 根据 appId 获取 AI 代码生成服务实例（带本地缓存）。
     * <p>
     * 兼容历史调用，默认使用 HTML 生成类型。
     *
     * @param appId 应用 ID，同时作为聊天记忆的唯一标识
     * @return AiCodeGeneratorService 实例
     */
    public AiCodeGeneratorService getAiCodeGeneratorService(long appId) {
        return getAiCodeGeneratorService(appId, CodeGenTypeEnum.HTML);
    }

    /**
     * 根据 appId 和代码生成类型获取 AI 代码生成服务实例（带本地缓存）。
     * <p>
     * 不同 {@link CodeGenTypeEnum} 使用不同的模型与工具组合：
     * <ul>
     *   <li>{@code VUE_PROJECT} — 推理流式模型 + 全套文件工具
     *       （写 / 读 / 读目录 / 改 / 删，由 {@link ToolManager} 统一提供，支持增量修改）</li>
     *   <li>{@code HTML} / {@code MULTI_FILE} — 默认聊天 + 流式模型</li>
     * </ul>
     *
     * @param appId       应用 ID
     * @param codeGenType 代码生成类型
     * @return AiCodeGeneratorService 实例
     */
    public AiCodeGeneratorService getAiCodeGeneratorService(long appId, CodeGenTypeEnum codeGenType) {
        String cacheKey = buildCacheKey(appId, codeGenType);
        return serviceCache.get(cacheKey, key -> createAiCodeGeneratorService(appId, codeGenType));
    }

    /**
     * 构建缓存键：{appId}_{codeGenType}
     */
    private String buildCacheKey(long appId, CodeGenTypeEnum codeGenType) {
        return appId + "_" + codeGenType.getValue();
    }

    /**
     * 创建新的 AI 服务实例（绑定独立的聊天记忆）。
     *
     * @param appId       应用 ID
     * @param codeGenType 代码生成类型
     * @return 新构建的 AiCodeGeneratorService
     */
    private AiCodeGeneratorService createAiCodeGeneratorService(long appId, CodeGenTypeEnum codeGenType) {
        log.info("为 appId: {}, 类型: {} 创建新的 AI 服务实例", appId, codeGenType);

        // 根据代码生成类型选择不同的模型配置
        return switch (codeGenType) {
            // Vue 项目生成使用推理模型 + 工具调用
            case VUE_PROJECT -> {
                // 工具调用型生成必须给足窗口，理由见 VUE_MEMORY_WINDOW_SIZE 的注释
                yield AiServices.builder(AiCodeGeneratorService.class)
                        // getObject() 每次拿到的都是新的 prototype 实例 —— 这里才是「多例」真正生效的地方。
                        // 若改成注入单例，启动时只创建一次，多个请求仍会排队抢同一个实例。
                        .streamingChatModel(reasoningStreamingModelProvider.getObject())
                        .chatMemoryProvider(memoryId -> buildChatMemory(appId, VUE_MEMORY_WINDOW_SIZE))
                        // 必须是数组，且显式转成 Object[]：让「把这个数组摊开成一个个工具」这件事
                        // 在代码上一眼可辨。若传 List，泛型不变性会让它匹配不上 tools(Collection<Object>)，
                        // 退化成「把整个 List 当成一个工具对象」，结果是工具静默地一个都没注册。
                        .tools((Object[]) toolManager.getAllTools())
                        .hallucinatedToolNameStrategy(toolExecutionRequest ->
                                ToolExecutionResultMessage.from(toolExecutionRequest,
                                        "Error: there is no tool called " + toolExecutionRequest.name()))
                        // 输入护轨：用户输入进模型之前先过一遍安全检查
                        .inputGuardrails(promptSafetyInputGuardrail)
                        .build();
            }

            // HTML 和多文件生成使用默认模型
            case HTML, MULTI_FILE -> AiServices.builder(AiCodeGeneratorService.class)
                    .chatModel(chatModel)
                    .streamingChatModel(htmlStreamingModelProvider.getObject())
                    .chatMemory(buildChatMemory(appId, DEFAULT_MEMORY_WINDOW_SIZE))
                    .inputGuardrails(promptSafetyInputGuardrail)
                    .build();

            default -> throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "不支持的代码生成类型: " + codeGenType.getValue());
        };
    }

    /**
     * 构建（并预热）某个 appId 的聊天记忆。
     * <p>
     * 记忆以 appId 为标识存在 Redis 里，同一个应用的「生成」和后续「可视化修改」
     * 共用一个窗口，所以窗口大小必须能同时装下这两段对话。
     *
     * @param appId       应用 ID，同时作为聊天记忆的唯一标识
     * @param windowSize  窗口内最多保留的消息条数
     * @return 已加载历史对话的聊天记忆
     */
    private MessageWindowChatMemory buildChatMemory(long appId, int windowSize) {
        MessageWindowChatMemory chatMemory = MessageWindowChatMemory
                .builder()
                .id(appId)
                .chatMemoryStore(redisChatMemoryStore)
                .maxMessages(windowSize)
                .build();
        // 从数据库加载历史对话到记忆中
        chatHistoryService.loadChatHistoryToMemory(appId, chatMemory, windowSize);
        return chatMemory;
    }

    /**
     * 默认 AI 代码生成服务 Bean（使用 appId=0 + HTML 类型作为默认记忆窗口）
     * 主要用于单元测试、或不需要多应用隔离的场景
     */
    @Bean
    public AiCodeGeneratorService aiCodeGeneratorService() {
        return getAiCodeGeneratorService(0L, CodeGenTypeEnum.HTML);
    }
}