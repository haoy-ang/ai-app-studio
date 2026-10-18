package com.haoy.aiapp.eval;

import com.haoy.aiapp.constant.AppConstant;
import com.haoy.aiapp.langgraph4j.CodeGenWorkflow;
import com.haoy.aiapp.langgraph4j.state.WorkflowContext;
import com.haoy.aiapp.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link WorkflowCodeGenerator} 的适配逻辑测试
 * <p>
 * 它只测「评估与 AI 链路之间那层胶水」，不碰大模型 ——
 * 所以工作流整个被打桩，跑起来零 token。
 * <p>
 * 重点是一条：<b>生成前必须把该 appId 的旧产物删干净</b>。
 * 这条不做，评估会在「上一轮残留」上判分，报告里显示通过但其实是旧文件 —— 比红更危险。
 *
 * @author <a>程序员haoy</a>
 */
class WorkflowCodeGeneratorTest {

    /**
     * 评估专用 appId 段里的一个值，避免碰到真实应用的产物目录
     */
    private static final long EVAL_APP_ID = 900_777L;

    private Path outputRoot;

    @BeforeEach
    void setUp() {
        outputRoot = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR);
    }

    @Test
    void shouldDeletePreviousArtifactsBeforeGenerating() throws IOException {
        // 上一轮留下的残骸：一个能骗过检查器的「完整」HTML 产物
        Path htmlDir = createStaleArtifact("html_" + EVAL_APP_ID);
        assertTrue(Files.exists(htmlDir.resolve("index.html")));

        WorkflowContext context = WorkflowContext.builder()
                .generationType(CodeGenTypeEnum.HTML)
                .generatedCodeDir(htmlDir.toString())
                .build();

        CodeGenerator.Generated generated = newGenerator(context).generate(evalCase(), EVAL_APP_ID);

        assertTrue(generated.succeeded(), "工作流返回正常时适配层应判定成功");
        assertEquals(CodeGenTypeEnum.HTML, generated.actualType());

        // 旧文件必须已经被清掉（本轮工作流是桩，不会再写文件，所以目录应当为空或不存在）
        if (Files.exists(htmlDir)) {
            try (var files = Files.list(htmlDir)) {
                assertEquals(0, files.count(), "生成前必须清空旧产物，否则评估会读到上一轮的残留");
            }
        }
    }

    @Test
    void shouldCleanAllThreeTypeDirsSinceTypeIsUnknownBeforeRouting() throws IOException {
        Path htmlDir = createStaleArtifact("html_" + EVAL_APP_ID);
        Path multiDir = createStaleArtifact("multi_file_" + EVAL_APP_ID);
        Path vueDir = createStaleArtifact("vue_project_" + EVAL_APP_ID);

        WorkflowContext context = WorkflowContext.builder()
                .generationType(CodeGenTypeEnum.MULTI_FILE)
                .generatedCodeDir(multiDir.toString())
                .build();

        newGenerator(context).generate(evalCase(), EVAL_APP_ID);

        // 生成类型要等路由完才知道，所以三种目录都得先清 —— 留着哪个都可能被误读
        assertFalse(Files.exists(htmlDir.resolve("index.html")), "HTML 旧产物没清");
        assertFalse(Files.exists(multiDir.resolve("index.html")), "多文件旧产物没清");
        assertFalse(Files.exists(vueDir.resolve("index.html")), "Vue 旧产物没清");
    }

    @Test
    void shouldTolerateMissingDirs() {
        // 第一次跑评估时目录根本不存在，不能因此报错
        WorkflowContext context = WorkflowContext.builder()
                .generationType(CodeGenTypeEnum.HTML)
                .generatedCodeDir(outputRoot.resolve("html_" + EVAL_APP_ID).toString())
                .build();

        CodeGenerator.Generated generated = newGenerator(context).generate(evalCase(), EVAL_APP_ID);

        assertTrue(generated.succeeded(), "目录不存在时应正常生成，而不是抛异常");
    }

    @Test
    void shouldReportFailureWhenContextIncomplete() {
        CodeGenerator.Generated noType = newGenerator(WorkflowContext.builder()
                .generatedCodeDir(outputRoot.toString())
                .build()).generate(evalCase(), EVAL_APP_ID);
        assertFalse(noType.succeeded());
        assertTrue(noType.error().contains("生成类型"), "错误信息要说清缺什么，实际=" + noType.error());

        CodeGenerator.Generated noDir = newGenerator(WorkflowContext.builder()
                .generationType(CodeGenTypeEnum.HTML)
                .build()).generate(evalCase(), EVAL_APP_ID);
        assertFalse(noDir.succeeded());
        assertTrue(noDir.error().contains("代码目录"), "错误信息要说清缺什么，实际=" + noDir.error());
    }

    @Test
    void shouldSwallowWorkflowExceptionInsteadOfBreakingWholeRun() {
        CodeGenWorkflow broken = mock(CodeGenWorkflow.class);
        when(broken.executeWorkflow(anyString(), anyLong()))
                .thenThrow(new IllegalStateException("模型 429 限流"));

        CodeGenerator.Generated generated = new WorkflowCodeGenerator(broken).generate(evalCase(), EVAL_APP_ID);

        assertFalse(generated.succeeded());
        assertTrue(generated.error().contains("429"), "异常信息要透到报告里，实际=" + generated.error());
        assertNull(generated.artifactDir());
        assertNotNull(generated.error());
    }

    /**
     * 造一个「看起来像正常产物」的旧目录 —— 检查器会判它通过，正是要防的假阳性
     */
    private Path createStaleArtifact(String dirName) throws IOException {
        Path dir = outputRoot.resolve(dirName);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("index.html"),
                "<!DOCTYPE html><html><head><title>上一轮</title></head><body><h1>旧产物</h1></body></html>");
        return dir;
    }

    private WorkflowCodeGenerator newGenerator(WorkflowContext context) {
        CodeGenWorkflow workflow = mock(CodeGenWorkflow.class);
        when(workflow.executeWorkflow(anyString(), anyLong())).thenReturn(context);
        return new WorkflowCodeGenerator(workflow);
    }

    private EvalCase evalCase() {
        return new EvalCase("unit-test-case", "做一个只有标题的页面", CodeGenTypeEnum.HTML,
                List.of(), List.of(), 60, "胶水层测试，不关心产物");
    }
}
