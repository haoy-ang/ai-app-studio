package com.haoy.aiapp.langgraph4j;

import com.haoy.aiapp.ai.AiCodeGenTypeRoutingService;
import com.haoy.aiapp.constant.AppConstant;
import com.haoy.aiapp.core.AiCodeGeneratorFacade;
import com.haoy.aiapp.core.builder.VueProjectBuilder;
import com.haoy.aiapp.langgraph4j.ai.CodeQualityCheckService;
import com.haoy.aiapp.langgraph4j.ai.ImageCollectionService;
import com.haoy.aiapp.langgraph4j.state.WorkflowContext;
import com.haoy.aiapp.model.enums.CodeGenTypeEnum;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphRepresentation;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.File;

import static com.haoy.aiapp.langgraph4j.WorkflowTestSupport.IMAGE_TEXT;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P5 工作流测试 —— <b>用假实现跑通整张图，不打 LLM、不跑 npm</b>。
 * <p>
 * 这是一个最划算的测试：它验证的正是工作流的本质 ——
 * <b>「状态有没有沿着边一步步传下去」</b>。所有节点各自的逻辑 P4 已经单独测过了，
 * 这里只关心「图连得对不对、上下文有没有被后面的节点读到」。
 * 把外部依赖换成 Mock 之后，它能在 1 秒内跑完，可以天天跑。
 * <p>
 * ⚠️ 接上 P6 的质检节点之后，这个测试比原来多了一件事：
 * 质检节点会去<b>读生成目录里的真实文件</b>，所以假 facade 必须真的落盘（见 {@link WorkflowTestSupport}）。
 * <p>
 * 真正的端到端（真实 AI + 真实构建）见 {@link P5WorkflowRealTest}；
 * 条件边 / 循环边 / 计数刹车的专项测试见 {@link P6WorkflowConditionalTest}。
 *
 * @author <a>程序员haoy</a>
 */
class P5WorkflowTest {

    /**
     * 这几个 appId 只在本测试里用，跑完会把对应目录删掉，避免污染真实的 {@code html_0} 产物
     */
    private static final String[] TEMP_DIRS = {"html_990001", "html_990002", "html_990003", "html_990004"};

    @AfterAll
    static void cleanTempDirs() {
        deleteDirs(TEMP_DIRS);
    }

    /**
     * 组装一个「依赖全是假的」工作流
     */
    private static CodeGenWorkflow workflow(ImageCollectionService imageService,
                                            AiCodeGenTypeRoutingService routingService,
                                            AiCodeGeneratorFacade facade,
                                            CodeQualityCheckService qualityService,
                                            VueProjectBuilder builder) {
        return new CodeGenWorkflow(imageService, routingService, facade, qualityService, builder);
    }

    /**
     * 图必须包含全部 6 个节点 —— 节点名或边写错时，这里会先失败。
     */
    @Test
    void workflowShouldContainAllNodes() {
        CompiledGraph<MessagesState<String>> graph = workflow(
                imageService(), routing(CodeGenTypeEnum.HTML), facadeWritingCode(),
                qualityAlwaysPass(), mock(VueProjectBuilder.class))
                .createWorkflow();

        assertNotNull(graph);
        String mermaid = graph.getGraph(GraphRepresentation.Type.MERMAID).content();
        assertTrue(mermaid.contains(WorkflowNodeNames.IMAGE_COLLECTOR), "缺少图片收集节点");
        assertTrue(mermaid.contains(WorkflowNodeNames.PROMPT_ENHANCER), "缺少提示词增强节点");
        assertTrue(mermaid.contains(WorkflowNodeNames.ROUTER), "缺少智能路由节点");
        assertTrue(mermaid.contains(WorkflowNodeNames.CODE_GENERATOR), "缺少代码生成节点");
        assertTrue(mermaid.contains(WorkflowNodeNames.CODE_QUALITY_CHECK), "缺少代码质检节点");
        assertTrue(mermaid.contains(WorkflowNodeNames.PROJECT_BUILDER), "缺少项目构建节点");
    }

    /**
     * 端到端（假依赖）：跑完之后每个节点该写的字段都要有值。
     * <p>
     * ⚠️ HTML 类型走的是 {@code 质检通过 → skip_build → END}，最后执行的节点是<b>代码质检</b>
     * 而不是项目构建（这正是条件边存在的意义：HTML 压根不跑 npm）。
     * 但「最终可预览目录」这条契约仍要成立 —— 由 {@code normalizeResultDir} 补上。
     */
    @Test
    void executeWorkflowShouldFillEveryStageOutput() {
        VueProjectBuilder builder = mock(VueProjectBuilder.class);
        when(builder.buildProject(anyString())).thenReturn(true);

        WorkflowContext result = workflow(imageService(), routing(CodeGenTypeEnum.HTML),
                facadeWritingCode(), qualityAlwaysPass(), builder)
                .executeWorkflow("创建一个技术博客网站", 990001L);

        assertNotNull(result, "工作流必须返回最终上下文");
        // 图片收集
        assertEquals(IMAGE_TEXT, result.getImageListStr());
        // 提示词增强
        assertTrue(result.getEnhancedPrompt().contains("创建一个技术博客网站"));
        assertTrue(result.getEnhancedPrompt().contains("https://x/1.png"), "图片必须被拼进增强提示词");
        // 智能路由
        assertEquals(CodeGenTypeEnum.HTML, result.getGenerationType());
        // 代码生成
        assertNotNull(result.getGeneratedCodeDir());
        // 代码质检
        assertNotNull(result.getQualityResult(), "质检节点必须产出结果");
        assertEquals(true, result.getQualityResult().getIsValid());
        // 最终目录：HTML 跳过了构建节点，由 normalizeResultDir 兜底成生成目录
        assertEquals(result.getGeneratedCodeDir(), result.getBuildResultDir(),
                "HTML 类型不构建，最终目录应等于生成目录");
        assertEquals("代码质量检查", result.getCurrentStep(), "HTML 走 skip_build，最后一个执行的节点是质检");
    }

    /**
     * 代码生成节点必须收到<b>增强后</b>的提示词，而不是原始需求 ——
     * 这条传错的话，图片就白收集了（网站还是占位图），而且不会有任何报错。
     */
    @Test
    void codeGeneratorShouldReceiveEnhancedPrompt() {
        AiCodeGeneratorFacade facade = facadeWritingCode();

        workflow(imageService(), routing(CodeGenTypeEnum.HTML), facade, qualityAlwaysPass(),
                mock(VueProjectBuilder.class))
                .executeWorkflow("做个博客", 990002L);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(facade).generateAndSaveCodeStream(captor.capture(), eq(CodeGenTypeEnum.HTML), eq(990002L));
        String sentToAi = captor.getValue();
        assertTrue(sentToAi.contains("做个博客"), "必须带上用户原始需求");
        assertTrue(sentToAi.contains("## 可用素材资源"), "必须带上素材段落");
        assertTrue(sentToAi.contains("https://x/1.png"), "必须带上图片地址");
    }

    /**
     * 传入的 appId 要一路走到生成目录上，否则文件会落到 {@code html_0} 里、和真实应用对不上。
     */
    @Test
    void appIdShouldReachGeneratedCodeDir() {
        WorkflowContext result = workflow(imageService(), routing(CodeGenTypeEnum.HTML),
                facadeWritingCode(), qualityAlwaysPass(), mock(VueProjectBuilder.class))
                .executeWorkflow("做个博客", 990003L);

        String expected = AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator
                + CodeGenTypeEnum.HTML.getValue() + "_990003";
        assertEquals(expected, result.getGeneratedCodeDir());
        assertEquals(990003L, result.getAppId());
    }

    /**
     * HTML 类型不应该触发 npm 构建（省一次几十秒的构建）。
     */
    @Test
    void htmlShouldNotTriggerBuild() {
        VueProjectBuilder builder = mock(VueProjectBuilder.class);

        workflow(imageService(), routing(CodeGenTypeEnum.HTML), facadeWritingCode(),
                qualityAlwaysPass(), builder)
                .executeWorkflow("做个博客", 990004L);

        verify(builder, never()).buildProject(anyString());
    }
}
