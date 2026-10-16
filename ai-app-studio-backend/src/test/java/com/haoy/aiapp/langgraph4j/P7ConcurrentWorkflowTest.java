package com.haoy.aiapp.langgraph4j;

import com.haoy.aiapp.core.builder.VueProjectBuilder;
import com.haoy.aiapp.langgraph4j.ai.ImageCollectionPlanService;
import com.haoy.aiapp.langgraph4j.model.ImageCategoryEnum;
import com.haoy.aiapp.langgraph4j.model.ImageCollectionPlan;
import com.haoy.aiapp.langgraph4j.model.ImageResource;
import com.haoy.aiapp.langgraph4j.node.concurrent.ConcurrentImageCollectorNode;
import com.haoy.aiapp.langgraph4j.state.WorkflowContext;
import com.haoy.aiapp.langgraph4j.tools.ImageSearchTool;
import com.haoy.aiapp.langgraph4j.tools.LogoGeneratorTool;
import com.haoy.aiapp.langgraph4j.tools.MermaidDiagramTool;
import com.haoy.aiapp.langgraph4j.tools.UndrawIllustrationTool;
import com.haoy.aiapp.model.enums.CodeGenTypeEnum;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.haoy.aiapp.langgraph4j.WorkflowTestSupport.deleteDirs;
import static com.haoy.aiapp.langgraph4j.WorkflowTestSupport.facadeWritingCode;
import static com.haoy.aiapp.langgraph4j.WorkflowTestSupport.imageService;
import static com.haoy.aiapp.langgraph4j.WorkflowTestSupport.qualityAlwaysPass;
import static com.haoy.aiapp.langgraph4j.WorkflowTestSupport.routing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P7 测试 —— <b>并发图片收集（规划 + 并发执行）</b>
 * <p>
 * 串行版是「AI 想一步、我们做一步」的多轮工具调用，每多一类图就多一次完整的大模型请求，
 * 又慢又贵。并发版换了个思路：先用<b>一次</b> AI 调用拿到完整计划，剩下四类收集都是纯 I/O，
 * 直接并起来跑。所以这一期要钉死的是三件事：
 * <ol>
 *   <li><b>规划只调一次</b> —— 这是并发版省时间的根源，退化成「每类图都调一次 AI」就白干了；</li>
 *   <li><b>任务真的并行</b> —— 不是把代码写成 CompletableFuture 就自动并行，这里用「峰值并发数」实测；</li>
 *   <li><b>单点失败不拖垮整体</b> —— 一个图源超时，只是少几张图，不能让整次网站生成失败。</li>
 * </ol>
 * 全部用 Mock + 假实现，不打网络、不打 LLM。
 *
 * @author <a>程序员haoy</a>
 */
class P7ConcurrentWorkflowTest {

    private static final String[] TEMP_DIRS = {"html_990201"};

    @AfterAll
    static void cleanTempDirs() {
        deleteDirs(TEMP_DIRS);
    }

    // ==================== 测试数据 ====================

    private static ImageCollectionPlan plan() {
        return new ImageCollectionPlan(
                List.of(new ImageCollectionPlan.ImageSearchTask("coding"),
                        new ImageCollectionPlan.ImageSearchTask("team")),
                List.of(new ImageCollectionPlan.IllustrationTask("happy")),
                List.of(new ImageCollectionPlan.DiagramTask("graph TD; A-->B", "系统架构")),
                List.of(new ImageCollectionPlan.LogoTask("科技公司 logo")));
    }

    private static ImageCollectionPlanService planService(ImageCollectionPlan result) {
        ImageCollectionPlanService service = mock(ImageCollectionPlanService.class);
        when(service.planImageCollection(anyString())).thenReturn(result);
        return service;
    }

    private static ImageResource image(ImageCategoryEnum category, String url) {
        return ImageResource.builder().category(category).description(category.getText()).url(url).build();
    }

    private static ImageSearchTool contentTool() {
        ImageSearchTool tool = mock(ImageSearchTool.class);
        when(tool.searchContentImages(anyString()))
                .thenReturn(List.of(image(ImageCategoryEnum.CONTENT, "https://cdn.example.com/content.png")));
        return tool;
    }

    private static UndrawIllustrationTool illustrationTool() {
        UndrawIllustrationTool tool = mock(UndrawIllustrationTool.class);
        when(tool.searchIllustrations(anyString()))
                .thenReturn(List.of(image(ImageCategoryEnum.ILLUSTRATION, "https://cdn.example.com/illu.svg")));
        return tool;
    }

    private static MermaidDiagramTool diagramTool() {
        MermaidDiagramTool tool = mock(MermaidDiagramTool.class);
        when(tool.generateArchitectureDiagram(anyString(), anyString()))
                .thenReturn(List.of(image(ImageCategoryEnum.ARCHITECTURE, "https://cdn.example.com/arch.svg")));
        return tool;
    }

    private static LogoGeneratorTool logoTool() {
        LogoGeneratorTool tool = mock(LogoGeneratorTool.class);
        when(tool.generateLogos(anyString()))
                .thenReturn(List.of(image(ImageCategoryEnum.LOGO, "https://cdn.example.com/logo.png")));
        return tool;
    }

    /**
     * 跑一个节点：构造状态 → apply → 取回上下文
     */
    private static WorkflowContext run(AsyncNodeAction<MessagesState<String>> node, WorkflowContext context) {
        MessagesState<String> state = new MessagesState<>(WorkflowContext.saveContext(context));
        Map<String, Object> result = node.apply(state).join();
        WorkflowContext output = WorkflowContext.getContext(new MessagesState<>(result));
        assertNotNull(output, "节点必须返回更新后的 WorkflowContext");
        return output;
    }

    // ==================== 节点级 ====================

    /**
     * 一份计划里的四类任务都要被执行，结果汇总进 {@code imageList}。
     * <p>
     * 计划里有 2 个内容图任务 + 各 1 个插画/架构图/Logo → 总量必须是 2+1+1+1 = 5。
     */
    @Test
    void concurrentNodeShouldCollectAllFourKinds() {
        ImageCollectionPlanService planService = planService(plan());
        ImageSearchTool content = contentTool();
        UndrawIllustrationTool illu = illustrationTool();
        MermaidDiagramTool diagram = diagramTool();
        LogoGeneratorTool logo = logoTool();

        WorkflowContext context = run(
                ConcurrentImageCollectorNode.create(planService, content, illu, diagram, logo),
                WorkflowContext.builder().originalPrompt("做一个技术博客").build());

        assertEquals(5, context.getImageList().size(), "四类任务的产出必须全部汇总进来");
        assertEquals("图片收集", context.getCurrentStep());
        // 规划只调一次 —— 这是并发版省时间的根源
        verify(planService, times(1)).planImageCollection(anyString());
        verify(content, times(2)).searchContentImages(anyString());
        verify(illu).searchIllustrations("happy");
        verify(diagram).generateArchitectureDiagram(eq("graph TD; A-->B"), anyString());
        verify(logo).generateLogos(anyString());
    }

    /**
     * <b>并发真的生效了吗？</b>
     * <p>
     * 把任务写成 {@code CompletableFuture} 不等于它们会并行 —— 用错线程池（比如塞进单线程或
     * 有界池）就会退化成串行。所以这里让每个任务「睡 300ms 并记录同时在跑的峰值」，
     * 只要峰值 ≥ 2 就证明确实有多个任务同时在跑。
     */
    @Test
    void tasksShouldActuallyRunInParallel() {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();

        ImageSearchTool content = mock(ImageSearchTool.class);
        when(content.searchContentImages(anyString()))
                .thenAnswer(invocation -> slowCall(inFlight, maxInFlight, ImageCategoryEnum.CONTENT));
        UndrawIllustrationTool illu = mock(UndrawIllustrationTool.class);
        when(illu.searchIllustrations(anyString()))
                .thenAnswer(invocation -> slowCall(inFlight, maxInFlight, ImageCategoryEnum.ILLUSTRATION));
        MermaidDiagramTool diagram = mock(MermaidDiagramTool.class);
        when(diagram.generateArchitectureDiagram(anyString(), anyString()))
                .thenAnswer(invocation -> slowCall(inFlight, maxInFlight, ImageCategoryEnum.ARCHITECTURE));
        LogoGeneratorTool logo = mock(LogoGeneratorTool.class);
        when(logo.generateLogos(anyString()))
                .thenAnswer(invocation -> slowCall(inFlight, maxInFlight, ImageCategoryEnum.LOGO));

        WorkflowContext context = run(
                ConcurrentImageCollectorNode.create(planService(plan()), content, illu, diagram, logo),
                WorkflowContext.builder().originalPrompt("做一个技术博客").build());

        assertTrue(maxInFlight.get() >= 2, "任务必须真的并行执行，实测峰值并发=" + maxInFlight.get());
        assertEquals(5, context.getImageList().size());
    }

    /**
     * 单个图源超时/报错 → 只少几张图，不能把整次收集带崩（图片本来就是增强项）。
     */
    @Test
    void singleToolFailureShouldNotBreakCollection() {
        ImageSearchTool brokenContent = mock(ImageSearchTool.class);
        when(brokenContent.searchContentImages(anyString()))
                .thenThrow(new RuntimeException("Wikimedia Read timed out"));

        WorkflowContext context = run(
                ConcurrentImageCollectorNode.create(planService(plan()), brokenContent,
                        illustrationTool(), diagramTool(), logoTool()),
                WorkflowContext.builder().originalPrompt("做一个技术博客").build());

        // 内容图这一路全挂了，其余 3 类照常：1 + 1 + 1 = 3
        assertEquals(3, context.getImageList().size(), "一路失败不该影响其他几路");
        assertEquals("图片收集", context.getCurrentStep());
    }

    /**
     * 连「规划」这一步都失败（比如 AI 被限流）→ 返回空列表、不抛异常，工作流继续往下走。
     */
    @Test
    void planFailureShouldLeaveEmptyImageList() {
        ImageCollectionPlanService brokenPlan = mock(ImageCollectionPlanService.class);
        when(brokenPlan.planImageCollection(anyString())).thenThrow(new RuntimeException("deepseek 限流"));

        WorkflowContext context = run(
                ConcurrentImageCollectorNode.create(brokenPlan, contentTool(), illustrationTool(),
                        diagramTool(), logoTool()),
                WorkflowContext.builder().originalPrompt("做一个技术博客").build());

        assertNotNull(context.getImageList(), "失败也要给一个空列表，别留 null");
        assertTrue(context.getImageList().isEmpty(), "规划失败时收集不到任何图片");
    }

    /**
     * 计划本身为 null（AI 返回空结构化结果）→ 同样不能抛 NPE。
     */
    @Test
    void nullPlanShouldNotThrow() {
        WorkflowContext context = run(
                ConcurrentImageCollectorNode.create(planService(null), contentTool(), illustrationTool(),
                        diagramTool(), logoTool()),
                WorkflowContext.builder().originalPrompt("做一个技术博客").build());

        assertTrue(context.getImageList().isEmpty());
    }

    // ==================== 工作流级 ====================

    /**
     * 并发版工作流复用了父类整张图，只有图片收集那一步换了实现 ——
     * 产出走的是<b>结构化</b> {@code imageList}，并且要能被提示词增强节点正确拼进提示词。
     */
    @Test
    void concurrentWorkflowShouldFeedStructuredImagesIntoPrompt() {
        WorkflowContext result = new CodeGenConcurrentWorkflow(
                imageService(), routing(CodeGenTypeEnum.HTML), facadeWritingCode(), qualityAlwaysPass(),
                mock(VueProjectBuilder.class),
                planService(plan()), contentTool(), illustrationTool(), diagramTool(), logoTool())
                .executeWorkflow("做一个技术博客", 990201L);

        assertNotNull(result.getImageList(), "并发版产出的是结构化图片列表");
        assertEquals(5, result.getImageList().size());
        // 结构化图片必须被拼进增强提示词：带了分类中文名 + 描述 + URL
        assertTrue(result.getEnhancedPrompt().contains("https://cdn.example.com/content.png"),
                "结构化图片必须进增强提示词");
        assertTrue(result.getEnhancedPrompt().contains("内容图片："), "提示词里要带上分类，AI 才知道图怎么用");
    }

    /**
     * 模拟一次「慢调用」，并把同时在跑的峰值记下来
     */
    private static List<ImageResource> slowCall(AtomicInteger inFlight, AtomicInteger maxInFlight,
                                                ImageCategoryEnum category) {
        int current = inFlight.incrementAndGet();
        maxInFlight.accumulateAndGet(current, Math::max);
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        inFlight.decrementAndGet();
        return List.of(image(category, "https://cdn.example.com/" + category.getValue().toLowerCase() + ".png"));
    }
}
