package com.haoy.aiapp.core.handler;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.haoy.aiapp.ai.model.message.AiResponseMessage;
import com.haoy.aiapp.ai.model.message.StreamMessage;
import com.haoy.aiapp.ai.model.message.StreamMessageTypeEnum;
import com.haoy.aiapp.ai.model.message.ToolExecutedMessage;
import com.haoy.aiapp.ai.tools.BaseTool;
import com.haoy.aiapp.ai.tools.ToolManager;
import com.haoy.aiapp.constant.AppConstant;
import com.haoy.aiapp.core.builder.VueProjectBuilder;
import com.haoy.aiapp.model.entity.User;
import com.haoy.aiapp.model.enums.ChatHistoryMessageTypeEnum;
import com.haoy.aiapp.service.chathistory.ChatHistoryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.HashSet;
import java.util.Set;

/**
 * JSON 消息流处理器
 * <p>
 * 处理 VUE_PROJECT 模式的复杂流式响应（AI 文本 + 工具调用混在一起）。
 * 相比 {@link SimpleTextStreamHandler} 多了两件事：
 * <ol>
 *   <li><b>消息解析</b>：把 JSON 消息还原成对象，按类型分别处理 ——
 *       AI 文本直接转发；工具调用完成则交给对应的工具自己生成可读文本。</li>
 *   <li><b>工具输出格式化</b>：按工具名从 {@link ToolManager} 取出工具实例，
 *       由工具自己决定「这次调用该显示成什么样」。
 *       写文件显示文件内容、改文件显示替换前后、读文件只显示路径 ——
 *       这些差异都封在各自的工具类里，这里不需要写 if-else。</li>
 * </ol>
 * 新增工具时本类<b>不用改</b>：工厂会自动把新工具注册进 {@link ToolManager}。
 * <p>
 * 关键设计：**转发给前端的文本和落库的对话历史是同一份内容**，
 * 这样用户刷新页面后看到的历史，跟实时生成时看到的一模一样，前端不需要两套渲染逻辑。
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
@Component
public class JsonMessageStreamHandler {

    @Resource
    private VueProjectBuilder vueProjectBuilder;

    @Resource
    private ToolManager toolManager;

    /**
     * 处理 TokenStream 适配后的 JSON 消息流（VUE_PROJECT）
     *
     * @param originFlux         原始流（元素是 JSON 消息字符串）
     * @param chatHistoryService 对话历史服务
     * @param appId              应用 ID
     * @param loginUser          登录用户
     * @return 转发给前端的流
     */
    public Flux<String> handle(Flux<String> originFlux,
                               ChatHistoryService chatHistoryService,
                               long appId, User loginUser) {
        // 收集数据用于生成落库的对话历史
        StringBuilder chatHistoryStringBuilder = new StringBuilder();
        // 记录已经见过的工具 ID，用于判断是否是首次调用
        Set<String> seenToolIds = new HashSet<>();
        // Vue 工程模式的产物目录（构建也要落在这里）
        String projectPath = AppConstant.CODE_OUTPUT_ROOT_DIR + "/"
                + AppConstant.VUE_PROJECT_DIR_PREFIX + appId;
        return originFlux
                .map(chunk -> handleJsonMessageChunk(chunk, chatHistoryStringBuilder, seenToolIds))
                // 过滤掉空字符串，避免前端收到无意义的空事件
                .filter(StrUtil::isNotEmpty)
                // AI 已经写完全部文件，接下来要跑 npm 构建（实测约 60 秒）。
                // 用 concatWith 把「构建」变成流的一段，而不是塞进 doOnComplete：
                // 一是 doOnComplete 里已经发不出消息了（流已终止），用户会干等 60 秒没有任何反馈；
                // 二是这样 done 事件天然排在构建之后，前端「生成完成」= 预览确实就绪。
                // 放在这里而不是 AiCodeGeneratorFacade.processTokenStream 的原因见 buildAndReport 注释。
                .concatWith(Flux.defer(() -> buildAndReport(projectPath, chatHistoryStringBuilder)))
                .doOnComplete(() -> {
                    // 流式响应完成后，添加 AI 消息到对话历史
                    // （此时 chatHistoryStringBuilder 里已经包含了构建提示与结果，刷新后历史里也能看到）
                    String aiResponse = chatHistoryStringBuilder.toString();
                    if (StrUtil.isNotBlank(aiResponse)) {
                        chatHistoryService.addChatMessage(appId, aiResponse,
                                ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
                    }
                })
                .doOnError(error -> {
                    String errorMessage = "AI回复失败: " + error.getMessage();
                    chatHistoryService.addChatMessage(appId, errorMessage,
                            ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
                });
    }

    /**
     * 同步构建 Vue 项目，并把构建状态作为消息发给前端
     * <p>
     * 为什么「同步 + 阻塞」：Vue 的产物是 dist 目录，不构建完预览就是空的/旧的。
     * 阻塞的是 boundedElastic 线程（{@code processTokenStream} 用 subscribeOn 挪过去的那条），
     * 不占 Servlet 请求线程，所以不影响其它请求。
     * <p>
     * ⚠️ 为什么放在这里、而<b>不</b>放 {@code AiCodeGeneratorFacade.processTokenStream}：
     * 那个方法属于 {@code generateAndSaveCodeStream}，它有两个调用方 —— 聊天链路
     * （{@code AppServiceImpl}）和 AI 工作流（{@code CodeGeneratorNode}）。
     * 工作流自己有一个 {@code ProjectBuilderNode} 负责构建，若把构建塞进 facade，
     * 工作流就会<b>构建两次</b>，并让 {@code P5WorkflowTest} / {@code P6WorkflowConditionalTest} 失败。
     * 而本类只被聊天链路使用（{@code StreamHandlerExecutor} → {@code AppServiceImpl.chatToGenCode}），
     * 因此放这里既拿到了「同步构建 + 构建提示」的效果，又完全不波及工作流。
     *
     * @param projectPath 项目根目录
     * @param chatHistory 落库内容累积器（构建提示与结果都写进去）
     * @return 构建提示 + 构建结果的流
     */
    private Flux<String> buildAndReport(String projectPath, StringBuilder chatHistory) {
        String startTip = "\n\n⏳ 代码已写完，正在构建预览，请稍候…\n\n";
        chatHistory.append(startTip);
        return Flux.just(startTip)
                .concatWith(Flux.defer(() -> {
                    long start = System.currentTimeMillis();
                    boolean success;
                    try {
                        success = vueProjectBuilder.buildProject(projectPath);
                    } catch (Exception e) {
                        // 构建失败不能把流打断 —— 打断的话前端收不到 done，「转圈」会永远不停
                        log.error("Vue 项目构建异常: {}", e.getMessage(), e);
                        success = false;
                    }
                    long cost = (System.currentTimeMillis() - start) / 1000;
                    // 构建失败也照样收尾：用户在对话里能看见原因，并且流正常结束
                    String result = success
                            ? "\n\n✅ 预览已就绪（构建耗时 " + cost + " 秒）\n\n"
                            : "\n\n❌ 项目构建失败，预览可能不可用（可查看后端 npm-build.log）\n\n";
                    chatHistory.append(result);
                    return Flux.just(result);
                }));
    }

    /**
     * 解析并收集单条 JSON 消息
     *
     * @param chunk                     原始 JSON 消息
     * @param chatHistoryStringBuilder  落库内容累积器
     * @param seenToolIds               已见过的工具 ID 集合
     * @return 需要转发给前端的内容（空字符串表示不转发）
     */
    private String handleJsonMessageChunk(String chunk, StringBuilder chatHistoryStringBuilder,
                                          Set<String> seenToolIds) {
        // 单条消息解析失败不应该中断整个流，所以这里兜住异常
        try {
            StreamMessage streamMessage = JSONUtil.toBean(chunk, StreamMessage.class);
            StreamMessageTypeEnum typeEnum = StreamMessageTypeEnum.getEnumByValue(streamMessage.getType());
            if (typeEnum == null) {
                log.error("不支持的消息类型: {}", streamMessage.getType());
                return "";
            }
            return switch (typeEnum) {
                case AI_RESPONSE -> {
                    AiResponseMessage aiMessage = JSONUtil.toBean(chunk, AiResponseMessage.class);
                    String data = aiMessage.getData();
                    // AI 自然语言直接拼接转发
                    chatHistoryStringBuilder.append(data);
                    yield data;
                }
                case TOOL_EXECUTED -> {
                    ToolExecutedMessage toolExecutedMessage = JSONUtil.toBean(chunk, ToolExecutedMessage.class);
                    String toolName = toolExecutedMessage.getName();
                    BaseTool tool = toolManager.getTool(toolName);
                    if (tool == null) {
                        // AI 幻觉出一个不存在的工具名。此时它拿不到执行结果，会按 hallucinatedToolNameStrategy
                        // 返回的提示自我纠正，所以这里只记日志、不打断流，也不给用户显示无意义的内容。
                        log.warn("未注册的工具: {}，已忽略本次工具输出", toolName);
                        yield "";
                    }
                    JSONObject arguments = parseArgumentsQuietly(toolExecutedMessage.getArguments());
                    StringBuilder output = new StringBuilder();
                    // 同一个工具第一次出现时，加一行「选择工具」提示，让用户知道 AI 正在动手
                    if (toolExecutedMessage.getId() != null && seenToolIds.add(toolExecutedMessage.getId())) {
                        output.append(tool.generateToolRequestResponse());
                    }
                    // 具体显示成什么样由工具自己决定（写文件带内容、改文件带前后对比、读文件只带路径）
                    output.append(tool.generateToolExecutedResult(arguments));
                    String result = "\n\n" + output + "\n\n";
                    chatHistoryStringBuilder.append(result);
                    yield result;
                }
            };
        } catch (Exception e) {
            log.error("解析流式消息失败: {}, chunk: {}", e.getMessage(), chunk, e);
            return "";
        }
    }

    /**
     * 宽松解析工具调用参数
     * <p>
     * 参数串是 AI 生成的，偶尔会是空串或被截断的 JSON。
     * 直接 {@code JSONUtil.parseObj} 会抛异常，而外层 catch 一旦兜住就会把
     * 整条工具调用消息丢掉（用户看到的是「AI 用了工具但什么都没发生」）。
     * 所以这里降级成一个空 JSON：工具拿不到参数会回一句「参数校验失败」，
     * 至少用户能看见，AI 也能据此重试。
     *
     * @param arguments 工具调用参数字符串
     * @return 解析成功的 JSON 对象，解析失败时返回空对象
     */
    private JSONObject parseArgumentsQuietly(String arguments) {
        if (StrUtil.isBlank(arguments)) {
            return new JSONObject();
        }
        try {
            return JSONUtil.parseObj(arguments);
        } catch (Exception e) {
            log.warn("解析工具调用参数失败，已降级为空参数: {}, arguments: {}", e.getMessage(), arguments);
            return new JSONObject();
        }
    }
}
