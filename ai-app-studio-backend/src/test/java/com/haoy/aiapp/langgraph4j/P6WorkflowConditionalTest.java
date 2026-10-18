package com.haoy.aiapp.langgraph4j;

import com.haoy.aiapp.ai.AiCodeGenTypeRoutingService;
import com.haoy.aiapp.core.AiCodeGeneratorFacade;
import com.haoy.aiapp.core.builder.VueProjectBuilder;
import com.haoy.aiapp.langgraph4j.ai.CodeQualityCheckService;
import com.haoy.aiapp.langgraph4j.ai.ImageCollectionService;
import com.haoy.aiapp.langgraph4j.model.QualityResult;
import com.haoy.aiapp.langgraph4j.state.WorkflowContext;
import com.haoy.aiapp.model.enums.CodeGenTypeEnum;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static com.haoy.aiapp.langgraph4j.WorkflowTestSupport.deleteDirs;
import static com.haoy.aiapp.langgraph4j.WorkflowTestSupport.facadeWritingCode;
import static com.haoy.aiapp.langgraph4j.WorkflowTestSupport.imageService;
import static com.haoy.aiapp.langgraph4j.WorkflowTestSupport.qualityAlwaysFail;
import static com.haoy.aiapp.langgraph4j.WorkflowTestSupport.qualityAlwaysPass;
import static com.haoy.aiapp.langgraph4j.WorkflowTestSupport.routing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P6 测试 —— <b>条件边 + 质检循环边</b>
 * <p>
 * 这一期给工作流加上的价值是「一进一出两道分诊」：
 * <ul>
 *   <li><b>出口</b>：质检通过后按类型分流 —— Vue 去构建，HTML / 多文件直接结束（省一次几十秒的 npm build）；</li>
 *   <li><b>回头路</b>：质检不通过就带着错误清单退回代码生成节点重做。</li>
 * </ul>
 * <p>
 * 两条路径各有各的坑，所以测试也分两层：
 * <ol>
 *   <li><b>路由函数直接单测</b>（{@code routeAfterQualityCheck} / {@code routeBuildOrSkip}）——
 *       最便宜也最精准，把「什么条件走哪条边」一次钉死，不用真的跑图。</li>
 *   <li><b>整图级</b>——验证循环边真的回退了、回退时确实换了提示词、到达上限后刹车放行不会无限转。</li>
 * </ol>
 * <p>
 * ⚠️ 循环边是本项目最危险的一类边：只要<b>不设次数上限</b>，
 * 模型犯病时会一直重写文件、SSE 永不结束（实测 196 次 writeFile）。
 * 所以这里的「计数刹车」测试不是锦上添花，是防止线程被永久占住的保险丝验收。
 *
 * @author <a>程序员haoy</a>
 */
class P6WorkflowConditionalTest {

    private static final String[] TEMP_DIRS = {"html_990101", "html_990103", "vue_project_990102"};

    @AfterAll
    static void cleanTempDirs() {
        deleteDirs(TEMP_DIRS);
    }

    /**
     * 只用来调路由函数的空壳工作流（依赖全 Mock，不跑图）
     */
    private static CodeGenWorkflow router() {
        return new CodeGenWorkflow(mock(ImageCollectionService.class), mock(AiCodeGenTypeRoutingService.class),
                mock(AiCodeGeneratorFacade.class), mock(CodeQualityCheckService.class), mock(VueProjectBuilder.class));
    }

    private static MessagesState<String> state(WorkflowContext context) {
        return new MessagesState<>(WorkflowContext.saveContext(context));
    }

    private static QualityResult passing() {
        return QualityResult.builder().isValid(true).errors(List.of()).suggestions(List.of()).build();
    }

    private static QualityResult failing() {
        return QualityResult.builder().isValid(false).errors(List.of("语法错误")).suggestions(List.of()).build();
    }

    // ==================== 第一层：路由函数单测 ====================

    /**
     * 质检通过 + Vue 工程 → 必须去构建。
     */
    @Test
    void routeShouldBuildForPassingVue() {
        WorkflowContext context = WorkflowContext.builder()
                .generationType(CodeGenTypeEnum.VUE_PROJECT)
                .qualityResult(passing())
                .build();

        assertEquals("build", router().routeAfterQualityCheck(state(context)));
    }

    /**
     * 质检通过 + HTML → 跳过构建（这就是条件边省下来的那一次 npm build）。
     */
    @Test
    void routeShouldSkipBuildForPassingHtml() {
        WorkflowContext context = WorkflowContext.builder()
                .generationType(CodeGenTypeEnum.HTML)
                .qualityResult(passing())
                .build();

        assertEquals("skip_build", router().routeAfterQualityCheck(state(context)));
    }

    /**
     * 质检不通过且给了错误项、还有返工额度 → 回退重生成。
     */
    @Test
    void routeShouldFailWhenQualityCheckFails() {
        WorkflowContext context = WorkflowContext.builder()
                .generationType(CodeGenTypeEnum.HTML)
                .qualityResult(failing())
                .codeGenerationCount(0)
                .build();

        assertEquals("fail", router().routeAfterQualityCheck(state(context)));
    }

    /**
     * 已经生成到上限（含首次共 {@code MAX_CODE_GENERATION_ATTEMPTS} 次）→ 放弃返工、放行。
     * <p>
     * 这条就是「刹车」：没有它，AI 一直判不通过就会无限循环。
     */
    @Test
    void routeShouldGiveUpReworkAfterMaxAttempts() {
        WorkflowContext context = WorkflowContext.builder()
                .generationType(CodeGenTypeEnum.HTML)
                .qualityResult(failing())
                .codeGenerationCount(CodeGenWorkflow.MAX_CODE_GENERATION_ATTEMPTS)
                .build();

        assertEquals("skip_build", router().routeAfterQualityCheck(state(context)),
                "到达上限后必须放行（HTML 走 skip_build），不能再回退");
    }

    /**
     * 「判不通过但没给错误项」→ 视为无可修复信息、放行。
     * <p>
     * ⚠️ 这条钉的是一个真实的一致性缺陷：早期条件边只看 {@code isValid}，
     * 而代码生成节点要求 {@code errors} 非空 —— 结果就是「边判要返工、节点却拿不到错误」，
     * 白跑一轮。现在两边共用 {@link QualityResult#needsRework()}，不会再漂移。
     */
    @Test
    void routeShouldNotReworkWhenNoActionableErrors() {
        WorkflowContext context = WorkflowContext.builder()
                .generationType(CodeGenTypeEnum.HTML)
                .qualityResult(QualityResult.builder().isValid(false).errors(List.of()).build())
                .build();

        assertEquals("skip_build", router().routeAfterQualityCheck(state(context)),
                "没有错误项就不该返工");
    }

    /**
     * 状态里压根没有质检结果（布线/配置问题）→ 放行而不是当成失败，避免白烧 token。
     */
    @Test
    void routeShouldTolerateMissingQualityResult() {
        WorkflowContext context = WorkflowContext.builder()
                .generationType(CodeGenTypeEnum.HTML)
                .build();

        assertEquals("skip_build", router().routeAfterQualityCheck(state(context)));
    }

    /**
     * 连 WorkflowContext 都没有 → 直接结束，不能抛 NPE。
     */
    @Test
    void routeShouldTolerateMissingContext() {
        assertEquals("skip_build", router().routeAfterQualityCheck(new MessagesState<>(Map.of())));
    }

    // ==================== 第二层：整图验证循环与分流 ====================

    /**
     * 质检一直不通过 → 工作流必须「重生成到上限后刹车放行」，而不是无限转。
     * <p>
     * 同时验证返工提示词：首次用的是增强提示词，之后必须换成「带上错误清单的修复提示词」——
     * 这条传错的话，返工会拿原样需求再问一遍，等于白跑（而且不报错）。
     */
    @Test
    void failingQualityShouldRegenerateWithFixPromptThenStopAtBrake() {
        AiCodeGeneratorFacade facade = facadeWritingCode();

        WorkflowContext result = new CodeGenWorkflow(imageService(), routing(CodeGenTypeEnum.HTML), facade,
                qualityAlwaysFail(), mock(VueProjectBuilder.class))
                .executeWorkflow("做一个博客", 990101L);

        // 首次 + 2 次返工 = MAX_CODE_GENERATION_ATTEMPTS，之后刹车放行
        verify(facade, times(CodeGenWorkflow.MAX_CODE_GENERATION_ATTEMPTS))
                .generateAndSaveCodeStream(anyString(), eq(CodeGenTypeEnum.HTML), eq(990101L), any());
        assertEquals(CodeGenWorkflow.MAX_CODE_GENERATION_ATTEMPTS, result.getCodeGenerationCount().intValue(),
                "生成计数必须正好停在上限，说明刹车生效且没有多跑");
        // 计数到顶后仍然放行，最终目录照常给出（否则调用方拿不到产物）
        assertNotNull(result.getBuildResultDir(), "刹车放行后仍要给出最终目录");

        // 提示词：首次是增强提示词，第 2/3 次换成修复提示词
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(facade, times(CodeGenWorkflow.MAX_CODE_GENERATION_ATTEMPTS))
                .generateAndSaveCodeStream(captor.capture(), any(), any(), any());
        List<String> sentMessages = captor.getAllValues();
        assertFalse(sentMessages.get(0).contains("上次生成的代码存在以下问题"), "首次生成用的是增强提示词，不该带修复段");
        assertTrue(sentMessages.get(1).contains("上次生成的代码存在以下问题"), "第 2 次必须带上修复提示词");
        assertTrue(sentMessages.get(1).contains("存在语法错误，页面无法渲染"), "修复提示词里要带上具体错误项");
        assertTrue(sentMessages.get(2).contains("上次生成的代码存在以下问题"), "第 3 次依然是修复提示词");
    }

    /**
     * Vue 工程 + 质检通过 → 必须真的走构建分支、最终目录指向 {@code dist}。
     */
    @Test
    void passingVueProjectShouldRouteToBuilder() {
        VueProjectBuilder builder = mock(VueProjectBuilder.class);
        when(builder.buildProject(anyString())).thenReturn(true);

        WorkflowContext result = new CodeGenWorkflow(imageService(), routing(CodeGenTypeEnum.VUE_PROJECT),
                facadeWritingCode(), qualityAlwaysPass(), builder)
                .executeWorkflow("做一个后台管理系统", 990102L);

        verify(builder).buildProject(anyString());
        assertNotNull(result.getBuildResultDir());
        assertTrue(result.getBuildResultDir().endsWith("dist"), "Vue 构建成功后的最终目录必须是 dist");
        assertEquals("项目构建", result.getCurrentStep(), "Vue 类型最后执行的节点是项目构建");
    }

    /**
     * HTML + 质检通过 → 走 skip_build 分支，压根不碰构建器。
     * <p>
     * 与 {@link P5WorkflowTest#htmlShouldNotTriggerBuild()} 的区别：这里额外确认
     * <b>最终目录仍被兜底填上</b>（跳过的节点负责的字段必须有人补）。
     */
    @Test
    void passingHtmlShouldSkipBuilderButStillHaveResultDir() {
        VueProjectBuilder builder = mock(VueProjectBuilder.class);

        WorkflowContext result = new CodeGenWorkflow(imageService(), routing(CodeGenTypeEnum.HTML),
                facadeWritingCode(), qualityAlwaysPass(), builder)
                .executeWorkflow("做一个博客", 990103L);

        verify(builder, org.mockito.Mockito.never()).buildProject(anyString());
        assertNotNull(result.getBuildResultDir(), "跳过构建后最终目录要由 normalizeResultDir 兜底");
        assertEquals(result.getGeneratedCodeDir(), result.getBuildResultDir());
    }
}
