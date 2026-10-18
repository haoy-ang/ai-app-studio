package com.haoy.aiapp.langgraph4j;

import cn.hutool.json.JSONUtil;
import com.haoy.aiapp.ai.AiCodeGenTypeRoutingService;
import com.haoy.aiapp.ai.AiCodeGenTypeRoutingServiceFactory;
import com.haoy.aiapp.core.AiCodeGeneratorFacade;
import com.haoy.aiapp.core.builder.VueProjectBuilder;
import com.haoy.aiapp.exception.BusinessException;
import com.haoy.aiapp.exception.ErrorCode;
import com.haoy.aiapp.langgraph4j.ai.CodeQualityCheckService;
import com.haoy.aiapp.langgraph4j.ai.ImageCollectionService;
import com.haoy.aiapp.langgraph4j.node.CodeGeneratorNode;
import com.haoy.aiapp.langgraph4j.node.CodeQualityCheckNode;
import com.haoy.aiapp.langgraph4j.node.ImageCollectorNode;
import com.haoy.aiapp.langgraph4j.node.ProjectBuilderNode;
import com.haoy.aiapp.langgraph4j.node.PromptEnhancerNode;
import com.haoy.aiapp.langgraph4j.node.RouterNode;
import com.haoy.aiapp.langgraph4j.state.WorkflowContext;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphRepresentation;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.prebuilt.MessagesStateGraph;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;

import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;

/**
 * 代码生成工作流 —— 核心实现
 * <p>
 * 图长这样（六个节点 + 两条条件边）：
 * <pre>
 *   图片收集 → 提示词增强 → 智能路由 → 代码生成 → 代码质检 ─┬─ 质检通过且是 Vue 工程 → 项目构建 → END
 *                                          ↑                ├─ 质检通过且无需构建   → END
 *                                          └──── 质检失败 ────┘
 * </pre>
 * 和原来的单次调用相比，多出来的价值有两块：
 * <ol>
 *   <li><b>生成前先找到真实图片素材</b>，网站不再是清一色随机占位图；</li>
 *   <li><b>生成后先质检、不过就带着错误清单返工</b>，把「生成成功但其实跑不起来」挡在交付之前。</li>
 * </ol>
 * <p>
 * <b>为什么「质检」用节点而不是直接写成条件边？</b>
 * 因为质检本身是个重活（读文件 + 调 AI + 产出结构化结果），属于业务逻辑，该用节点；
 * 而「通过了没、下一步去哪」是纯流程控制，该用条件边。两者组合才是完整方案。
 * <p>
 * ⚠️ <b>循环边必须有刹车</b>：只要不设次数上限，AI 一直判不通过就会无限转。
 * 这里用 {@link WorkflowContext#getCodeGenerationCount()} 计数，超过
 * {@value #MAX_CODE_GENERATION_ATTEMPTS} 次生成后主动放弃修复、继续往后走。
 * <p>
 * <b>为什么做成 {@code @Component} + 构造器注入？</b>
 * 若用静态方法 + {@code SpringContextUtil} 反查容器，代价是多一个全局静态上下文、
 * 且节点依赖无法在测试里替换。这里把依赖从构造器灌进来、再传给各节点的 {@code create(依赖)}：
 * 运行时由 Spring 装配，测试里塞假实现就能验证「图连得对不对」。
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
@Component
public class CodeGenWorkflow {

    /**
     * 最多生成几次代码（含首次）。
     * <p>
     * 3 = 首次生成 + 最多 2 次「质检失败后返工」。定成常量而不是配置项，是因为这不是调优参数、
     * 而是「防止无限循环」的保险丝，不该被随意调大。
     */
    public static final int MAX_CODE_GENERATION_ATTEMPTS = 3;

    /**
     * 图执行的硬上限（框架级保险丝）—— 万一还漏了别的环，也不会把线程永远占住
     */
    private static final int MAX_ITERATIONS = 50;

    private final ImageCollectionService imageCollectionService;

    private final AiCodeGenTypeRoutingService aiCodeGenTypeRoutingService;

    /**
     * 备用路由服务 —— 主路由模型被限流 / 超时时接手判断
     * <p>
     * 为 null 表示只有主模型（单元测试里常见），此时路由失败会直接退回 HTML。
     */
    private final AiCodeGenTypeRoutingService fallbackRoutingService;

    private final AiCodeGeneratorFacade aiCodeGeneratorFacade;

    private final CodeQualityCheckService codeQualityCheckService;

    private final VueProjectBuilder vueProjectBuilder;

    /**
     * 生产用构造器：备用路由服务从工厂现取
     * <p>
     * 之所以传工厂而不是直接注第二个 {@code AiCodeGenTypeRoutingService}：容器里两个同类型 Bean
     * 会让「按类型注入」出现歧义。工厂是 {@code @Configuration}，注入它没有这个问题。
     *
     * @param routingServiceFactory 路由服务工厂
     */
    @Autowired
    public CodeGenWorkflow(ImageCollectionService imageCollectionService,
                           AiCodeGenTypeRoutingService aiCodeGenTypeRoutingService,
                           AiCodeGeneratorFacade aiCodeGeneratorFacade,
                           CodeQualityCheckService codeQualityCheckService,
                           VueProjectBuilder vueProjectBuilder,
                           AiCodeGenTypeRoutingServiceFactory routingServiceFactory) {
        this(imageCollectionService, aiCodeGenTypeRoutingService, aiCodeGeneratorFacade,
                codeQualityCheckService, vueProjectBuilder, routingServiceFactory.createFallbackRoutingService());
    }

    /**
     * 测试用构造器：只有主路由模型，失败直接退回 HTML
     * <p>
     * 保留它是为了让既有的确定性测试不必关心「降级」这件事 ——
     * 降级逻辑在 {@code RouterNode} 里单独测，见 {@code RouterNodeFallbackTest}。
     */
    public CodeGenWorkflow(ImageCollectionService imageCollectionService,
                           AiCodeGenTypeRoutingService aiCodeGenTypeRoutingService,
                           AiCodeGeneratorFacade aiCodeGeneratorFacade,
                           CodeQualityCheckService codeQualityCheckService,
                           VueProjectBuilder vueProjectBuilder) {
        // 这里的 null 必须强转：两个 6 参构造器只差最后一个参数的类型，不转编译器分不出来
        this(imageCollectionService, aiCodeGenTypeRoutingService, aiCodeGeneratorFacade,
                codeQualityCheckService, vueProjectBuilder, (AiCodeGenTypeRoutingService) null);
    }

    /**
     * 真正的赋值处 —— 上面两个构造器都会被落到这里
     */
    private CodeGenWorkflow(ImageCollectionService imageCollectionService,
                            AiCodeGenTypeRoutingService aiCodeGenTypeRoutingService,
                            AiCodeGeneratorFacade aiCodeGeneratorFacade,
                            CodeQualityCheckService codeQualityCheckService,
                            VueProjectBuilder vueProjectBuilder,
                            AiCodeGenTypeRoutingService fallbackRoutingService) {
        this.imageCollectionService = imageCollectionService;
        this.aiCodeGenTypeRoutingService = aiCodeGenTypeRoutingService;
        this.aiCodeGeneratorFacade = aiCodeGeneratorFacade;
        this.codeQualityCheckService = codeQualityCheckService;
        this.vueProjectBuilder = vueProjectBuilder;
        this.fallbackRoutingService = fallbackRoutingService;
    }

    /**
     * 创建「图片收集」节点 —— 留成可覆写的方法，是为了让并发版（{@link CodeGenConcurrentWorkflow}）
     * 只换这一个节点、复用整张图，而不是把布线代码抄一遍。
     *
     * @return 图片收集节点
     */
    protected AsyncNodeAction<MessagesState<String>> createImageCollectorNode() {
        return ImageCollectorNode.create(imageCollectionService);
    }

    /**
     * 创建（编译）工作流
     *
     * @return 编译后的图
     * @throws BusinessException 图连不上时抛出（节点名写错、缺边等）
     */
    public CompiledGraph<MessagesState<String>> createWorkflow() {
        try {
            CompiledGraph<MessagesState<String>> compiledGraph = new MessagesStateGraph<String>()
                    .addNode(WorkflowNodeNames.IMAGE_COLLECTOR, createImageCollectorNode())
                    .addNode(WorkflowNodeNames.PROMPT_ENHANCER, PromptEnhancerNode.create())
                    .addNode(WorkflowNodeNames.ROUTER, RouterNode.create(aiCodeGenTypeRoutingService, fallbackRoutingService))
                    .addNode(WorkflowNodeNames.CODE_GENERATOR, CodeGeneratorNode.create(aiCodeGeneratorFacade))
                    .addNode(WorkflowNodeNames.CODE_QUALITY_CHECK, CodeQualityCheckNode.create(codeQualityCheckService))
                    .addNode(WorkflowNodeNames.PROJECT_BUILDER, ProjectBuilderNode.create(vueProjectBuilder))

                    // 主干：一路向前
                    .addEdge(StateGraph.START, WorkflowNodeNames.IMAGE_COLLECTOR)
                    .addEdge(WorkflowNodeNames.IMAGE_COLLECTOR, WorkflowNodeNames.PROMPT_ENHANCER)
                    .addEdge(WorkflowNodeNames.PROMPT_ENHANCER, WorkflowNodeNames.ROUTER)
                    .addEdge(WorkflowNodeNames.ROUTER, WorkflowNodeNames.CODE_GENERATOR)
                    .addEdge(WorkflowNodeNames.CODE_GENERATOR, WorkflowNodeNames.CODE_QUALITY_CHECK)

                    // 质检之后分三路：要构建、跳过构建、返工重生成
                    .addConditionalEdges(WorkflowNodeNames.CODE_QUALITY_CHECK,
                            edge_async(this::routeAfterQualityCheck),
                            Map.of(
                                    "build", WorkflowNodeNames.PROJECT_BUILDER,
                                    "skip_build", StateGraph.END,
                                    "fail", WorkflowNodeNames.CODE_GENERATOR))

                    .addEdge(WorkflowNodeNames.PROJECT_BUILDER, StateGraph.END)
                    .compile();
            compiledGraph.setMaxIterations(MAX_ITERATIONS);
            return compiledGraph;
        } catch (GraphStateException e) {
            log.error("工作流创建失败: {}", e.getMessage());
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "工作流创建失败");
        }
    }

    /**
     * 质检之后往哪走
     * <p>
     * 三件事按顺序判断：能不能继续返工 → 要不要构建 → 结束。
     * <p>
     * ⚠️ {@code qualityResult == null} 要单独对待：它表示「质检节点压根没写状态」，
     * 属于布线/配置问题，返工重生成解决不了，
     * 只会白烧 token —— 所以这里按「放行」处理并打警告。
     *
     * @param state 当前状态
     * @return 目标节点名：{@code build} / {@code skip_build} / {@code fail}
     */
    String routeAfterQualityCheck(MessagesState<String> state) {
        WorkflowContext context = WorkflowContext.getContext(state);
        if (context == null) {
            log.warn("状态里没有 WorkflowContext，直接结束");
            return "skip_build";
        }
        if (context.getQualityResult() == null) {
            log.warn("质检节点没有产出结果，按通过处理（继续后续流程）");
            return routeBuildOrSkip(state);
        }
        // 通过、或「判不通过但没给出错误项」→ 都继续往前走。
        // 后者的含义是「没有可修复的信息」，让代码生成节点带着空修复提示词再跑一轮纯属白烧 token。
        // 判据来自 QualityResult#needsRework()，与代码生成节点用的是同一个方法，不会漂移。
        if (!context.getQualityResult().needsRework()) {
            log.info("代码质检通过（或未给出可执行的修复项），继续后续流程");
            return routeBuildOrSkip(state);
        }
        // 质检未通过：先看还有没有返工机会
        int generatedCount = context.getCodeGenerationCount() == null ? 0 : context.getCodeGenerationCount();
        if (generatedCount >= MAX_CODE_GENERATION_ATTEMPTS) {
            log.warn("已生成 {} 次仍未通过质检，放弃返工、继续后续流程（避免无限循环）", generatedCount);
            return routeBuildOrSkip(state);
        }
        log.warn("代码质检未通过，回退代码生成节点返工（第 {} 次生成即将开始）", generatedCount + 1);
        return "fail";
    }

    /**
     * 代码生成之后要不要构建：只有 Vue 工程需要 npm 构建，HTML / 多文件在生成时就落盘了。
     * <p>
     * 这就是「跳过构建 - 条件边」那条特性：把 if-else 从项目构建节点里挪到边上，
     * 好处是流程控制集中在一处、节点只管自己的业务。
     *
     * @param state 当前状态
     * @return {@code build} 或 {@code skip_build}
     */
    String routeBuildOrSkip(MessagesState<String> state) {
        WorkflowContext context = WorkflowContext.getContext(state);
        return context != null && context.getGenerationType() == com.haoy.aiapp.model.enums.CodeGenTypeEnum.VUE_PROJECT
                ? "build" : "skip_build";
    }

    /**
     * 执行工作流（脱离业务流程，appId 用 0）
     */
    public WorkflowContext executeWorkflow(String originalPrompt) {
        return executeWorkflow(originalPrompt, 0L);
    }

    /**
     * 执行工作流（同步，跑完才返回）
     *
     * @param originalPrompt 用户原始需求
     * @param appId          应用 ID，决定代码落到哪个目录
     * @return 最终上下文；拿不到状态时返回 null
     */
    public WorkflowContext executeWorkflow(String originalPrompt, Long appId) {
        CompiledGraph<MessagesState<String>> workflow = createWorkflow();
        WorkflowContext initialContext = buildInitialContext(originalPrompt, appId);

        logWorkflowGraph(workflow);
        log.info("开始执行代码生成工作流，appId: {}", appId);

        WorkflowContext finalContext = null;
        int stepCounter = 1;
        for (NodeOutput<MessagesState<String>> step : workflow.stream(
                WorkflowContext.saveContext(initialContext))) {
            log.info("--- 第 {} 步完成，节点: {} ---", stepCounter, step.node());
            WorkflowContext currentContext = WorkflowContext.getContext(step.state());
            if (currentContext != null) {
                finalContext = currentContext;
                log.info("当前步骤上下文: {}", currentContext);
            }
            stepCounter++;
        }
        normalizeResultDir(finalContext);
        log.info("代码生成工作流执行完成！");
        return finalContext;
    }

    /**
     * 补上「最终可预览目录」这条契约
     * <p>
     * ⚠️ 这是接上条件边之后才出现的一个新坑：非 Vue 类型质检通过后会走 {@code skip_build → END}，
     * <b>压根不进 {@code project_builder} 节点</b>，于是 {@code buildResultDir} 会一直是 null。
     * 而调用方（预览、部署）要的是「最终目录」，拿 null 会直接崩。
     * <p>
     * 规矩很简单：<b>跳过的节点所负责的状态字段，必须有人在别处补上</b>。
     * 这里统一收口 —— 没经过构建节点时，「最终目录」就等于「生成目录」。
     *
     * @param context 工作流结束时的上下文，可能为 null
     */
    private void normalizeResultDir(WorkflowContext context) {
        if (context == null) {
            return;
        }
        if (context.getBuildResultDir() == null && context.getGeneratedCodeDir() != null) {
            context.setBuildResultDir(context.getGeneratedCodeDir());
        }
    }

    /**
     * 执行工作流（Flux 流式输出版本）—— 每一步完成就推一个 SSE 事件
     * <p>
     * 为什么用 Flux 而不是框架自带的 SSE 支持：官方那套要和 ChatModel 绑定、不灵活；
     * 自己造 Flux 可以自由决定「推什么、什么时候推」，而且能直接接进本项目已有的
     * {@code Flux<String>} 响应式链路（和现有聊天接口同一套写法）。
     * <p>
     * ⚠️ 工作流是阻塞式的（内部含 {@code blockLast} 等待代码生成），必须丢到独立线程里跑，
     * 否则会把响应式线程占死。这里用虚拟线程，一个连接一个，不占平台线程池。
     *
     * @param originalPrompt 用户原始需求
     * @return SSE 事件流（每元素是完整的一段 {@code event:/data:} 文本）
     */
    public Flux<String> executeWorkflowWithFlux(String originalPrompt) {
        return executeWorkflowWithFlux(originalPrompt, 0L);
    }

    /**
     * 执行工作流（Flux 流式输出版本，可指定 appId）
     */
    public Flux<String> executeWorkflowWithFlux(String originalPrompt, Long appId) {
        return Flux.create(sink -> Thread.startVirtualThread(() -> {
            try {
                CompiledGraph<MessagesState<String>> workflow = createWorkflow();
                WorkflowContext initialContext = buildInitialContext(originalPrompt, appId);

                sink.next(formatSseEvent("workflow_start", Map.of(
                        "message", "开始执行代码生成工作流",
                        "originalPrompt", originalPrompt)));
                logWorkflowGraph(workflow);

                int stepCounter = 1;
                WorkflowContext finalContext = null;
                for (NodeOutput<MessagesState<String>> step : workflow.stream(
                        WorkflowContext.saveContext(initialContext))) {
                    WorkflowContext currentContext = WorkflowContext.getContext(step.state());
                    if (currentContext != null) {
                        finalContext = currentContext;
                        sink.next(formatSseEvent("step_completed", Map.of(
                                "stepNumber", stepCounter,
                                "node", step.node(),
                                "currentStep", String.valueOf(currentContext.getCurrentStep()))));
                        log.info("--- 第 {} 步完成，节点: {} ---", stepCounter, step.node());
                    }
                    stepCounter++;
                }

                normalizeResultDir(finalContext);
                Map<String, Object> completedData = new HashMap<>();
                completedData.put("message", "代码生成工作流执行完成");
                if (finalContext != null) {
                    // 把最终目录一并回传：前端/调用方据此才能预览或部署
                    completedData.put("buildResultDir", String.valueOf(finalContext.getBuildResultDir()));
                    completedData.put("generationType", String.valueOf(finalContext.getGenerationType()));
                }
                sink.next(formatSseEvent("workflow_completed", completedData));
                log.info("代码生成工作流执行完成！");
                sink.complete();
            } catch (Exception e) {
                log.error("工作流执行失败: {}", e.getMessage(), e);
                // 先把错误当成一条正常事件推给前端，再结束流：
                // 直接 sink.error 的话 SSE 会断开，前端按「正常关闭」处理、什么都看不到
                sink.next(formatSseEvent("workflow_error", Map.of(
                        "error", String.valueOf(e.getMessage()),
                        "message", "工作流执行失败")));
                sink.complete();
            }
        }));
    }

    /**
     * 把事件格式化成 SSE 文本
     * <p>
     * 返回的是完整一段（含结尾空行）而不是结构化对象，因为要直接塞进
     * {@code Flux<String>} 并用 {@code text/event-stream} 返回给浏览器。
     *
     * @param eventType 事件名（前端用 {@code addEventListener} 监听）
     * @param data      事件数据，会被序列化成 JSON
     * @return SSE 文本
     */
    static String formatSseEvent(String eventType, Object data) {
        try {
            String jsonData = JSONUtil.toJsonStr(data);
            return "event: " + eventType + "\ndata: " + jsonData + "\n\n";
        } catch (Exception e) {
            log.error("格式化 SSE 事件失败: {}", e.getMessage());
            return "event: error\ndata: {\"error\":\"格式化失败\"}\n\n";
        }
    }

    private WorkflowContext buildInitialContext(String originalPrompt, Long appId) {
        return WorkflowContext.builder()
                .originalPrompt(originalPrompt)
                .currentStep("初始化")
                .appId(appId)
                .build();
    }

    private void logWorkflowGraph(CompiledGraph<MessagesState<String>> workflow) {
        // 把 Mermaid 图打出来：节点/边接错时，这段日志比一行行读代码快得多
        GraphRepresentation graph = workflow.getGraph(GraphRepresentation.Type.MERMAID);
        log.info("工作流图:\n{}", graph.content());
    }
}
