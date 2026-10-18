package com.haoy.aiapp.langgraph4j.node;

import com.haoy.aiapp.ai.AiCodeGenTypeRoutingService;
import com.haoy.aiapp.constant.AppConstant;
import com.haoy.aiapp.core.AiCodeGeneratorFacade;
import com.haoy.aiapp.core.builder.VueProjectBuilder;
import com.haoy.aiapp.langgraph4j.ai.CodeQualityCheckService;
import com.haoy.aiapp.langgraph4j.ai.ImageCollectionService;
import com.haoy.aiapp.langgraph4j.model.ImageCategoryEnum;
import com.haoy.aiapp.langgraph4j.model.ImageResource;
import com.haoy.aiapp.langgraph4j.model.QualityResult;
import com.haoy.aiapp.langgraph4j.state.WorkflowContext;
import com.haoy.aiapp.model.enums.CodeGenTypeEnum;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P4 工作节点契约测试 —— 全部用假实现 / Mockito，<b>不起 Spring 容器、不打 LLM、不跑 npm</b>。
 * <p>
 * 为什么每个节点都要单独钉一遍：工作节点的套路都是「读 WorkflowContext → 干活 → 写回 Context」，
 * 最典型的 bug 不是抛异常，而是<b>「节点跑了，但状态字段没写对」</b> ——
 * 工作流照样一路绿灯走到底，只是最后生成的网站没有图片、或者构建目录是错的。
 * 这类问题靠端到端测一次很难定位，拆到节点级别就一目了然。
 *
 * @author <a>程序员haoy</a>
 */
class P4NodesTest {

    // ==================== 图片收集 ====================

    /**
     * 收集到的文本必须落到 imageListStr，供提示词增强节点消费。
     */
    @Test
    void imageCollectorShouldWriteImageListStr() {
        ImageCollectionService service = prompt -> "{\"content\":[{\"url\":\"https://x/1.png\"}]}";
        WorkflowContext context = run(ImageCollectorNode.create(service),
                WorkflowContext.builder().originalPrompt("做个博客").build());

        assertEquals("{\"content\":[{\"url\":\"https://x/1.png\"}]}", context.getImageListStr());
        assertEquals("图片收集", context.getCurrentStep());
    }

    /**
     * 图片收集失败（超时/限流）不能让工作流挂掉，只能退化成「这次没有图片素材」。
     */
    @Test
    void imageCollectorShouldSurviveServiceFailure() {
        ImageCollectionService service = prompt -> {
            throw new RuntimeException("Read timed out");
        };
        WorkflowContext context = run(ImageCollectorNode.create(service),
                WorkflowContext.builder().originalPrompt("做个博客").build());

        assertEquals("", context.getImageListStr());
        assertEquals("图片收集", context.getCurrentStep());
    }

    // ==================== 提示词增强 ====================

    /**
     * 有结构化图片列表时，把每张图的「分类 + 描述 + URL」拼进增强提示词。
     */
    @Test
    void promptEnhancerShouldAppendStructuredImages() {
        // ⚠️ 必须断言节点「返回」的上下文：状态现在存的是 JSON 字符串，getContext 解析出来的是副本，
        // 节点不会再原地修改你传进去的那个对象（见 WorkflowContext#saveContext 的说明）
        WorkflowContext context = run(PromptEnhancerNode.create(), WorkflowContext.builder()
                .originalPrompt("做个技术博客")
                .imageList(List.of(
                        ImageResource.builder().category(ImageCategoryEnum.ARCHITECTURE)
                                .description("系统架构图").url("https://x/arch.svg").build(),
                        ImageResource.builder().category(ImageCategoryEnum.CONTENT)
                                .description("编程").url("https://x/code.jpg").build()))
                .build());

        String enhanced = context.getEnhancedPrompt();
        assertTrue(enhanced.startsWith("做个技术博客"), "必须在原始需求之后追加素材清单");
        assertTrue(enhanced.contains("## 可用素材资源"));
        assertTrue(enhanced.contains("架构图片：系统架构图（https://x/arch.svg）"));
        assertTrue(enhanced.contains("https://x/code.jpg"));
        assertEquals("提示词增强", context.getCurrentStep());
    }

    /**
     * 只有文本形式（工具调用方案）时，也要把文本拼进去 —— 两条收集路线共用这一个节点。
     */
    @Test
    void promptEnhancerShouldAppendTextImages() {
        WorkflowContext context = run(PromptEnhancerNode.create(), WorkflowContext.builder()
                .originalPrompt("做个博客")
                .imageListStr("{\"content\":[{\"url\":\"https://x/1.png\"}]}")
                .build());

        assertTrue(context.getEnhancedPrompt().contains("https://x/1.png"));
        assertTrue(context.getEnhancedPrompt().contains("## 可用素材资源"));
    }

    /**
     * 一张图都没收集到时，增强提示词必须<b>等于</b>原始需求（不能多出一个空的素材段落）。
     */
    @Test
    void promptEnhancerShouldKeepOriginalPromptWhenNoImages() {
        WorkflowContext context = run(PromptEnhancerNode.create(),
                WorkflowContext.builder().originalPrompt("做个博客").build());
        assertEquals("做个博客", context.getEnhancedPrompt());
    }

    // ==================== 智能路由 ====================

    @Test
    void routerShouldWriteGenerationType() {
        AiCodeGenTypeRoutingService routingService = mock(AiCodeGenTypeRoutingService.class);
        when(routingService.routeCodeGenType(anyString())).thenReturn(CodeGenTypeEnum.VUE_PROJECT);

        WorkflowContext context = run(RouterNode.create(routingService),
                WorkflowContext.builder().originalPrompt("做个后台管理系统").build());

        assertEquals(CodeGenTypeEnum.VUE_PROJECT, context.getGenerationType());
        assertEquals("智能路由", context.getCurrentStep());
    }

    /**
     * 路由要调一次 AI，失败时必须退化成 HTML 而不是抛异常。
     */
    @Test
    void routerShouldFallbackToHtmlOnFailure() {
        AiCodeGenTypeRoutingService routingService = mock(AiCodeGenTypeRoutingService.class);
        when(routingService.routeCodeGenType(anyString())).thenThrow(new RuntimeException("500"));

        WorkflowContext context = run(RouterNode.create(routingService),
                WorkflowContext.builder().originalPrompt("随便做点啥").build());

        assertEquals(CodeGenTypeEnum.HTML, context.getGenerationType());
        // 降级必须留痕：否则报告里「类型不对」会被误读成「模型判断力差」
        assertEquals(Boolean.TRUE, context.getRoutingDegraded());
    }

    /**
     * 主路由模型被限流时，不能直接退回 HTML —— 换备用模型再判一次。
     * <p>
     * 这条是实测倒逼出来的：百炼 qwen-turbo 返回 429 时，直接退回 HTML
     * 会让「要 Vue 后台」的请求变成一张 HTML 页，用户完全看不出是限流造成的。
     */
    @Test
    void routerShouldUseFallbackModelWhenPrimaryFails() {
        AiCodeGenTypeRoutingService primary = mock(AiCodeGenTypeRoutingService.class);
        when(primary.routeCodeGenType(anyString()))
                .thenThrow(new RuntimeException("429 Too Many Requests: limit_requests"));

        AiCodeGenTypeRoutingService fallback = mock(AiCodeGenTypeRoutingService.class);
        when(fallback.routeCodeGenType(anyString())).thenReturn(CodeGenTypeEnum.VUE_PROJECT);

        WorkflowContext context = run(RouterNode.create(primary, fallback),
                WorkflowContext.builder().originalPrompt("做个后台管理界面，三个页面，需要路由跳转").build());

        assertEquals(CodeGenTypeEnum.VUE_PROJECT, context.getGenerationType(),
                "备用模型判出来了就该用它，而不是退回 HTML");
        assertEquals(Boolean.TRUE, context.getRoutingDegraded(), "用了备用模型也算降级，必须留痕");
        assertTrue(context.getRoutingDegradeReason().contains("429"),
                "降级原因里要能看出是限流：" + context.getRoutingDegradeReason());
    }

    /**
     * 主、备都失败才退回 HTML，且原因里要能同时看到两边
     */
    @Test
    void routerShouldFallBackToHtmlOnlyWhenBothModelsFail() {
        AiCodeGenTypeRoutingService primary = mock(AiCodeGenTypeRoutingService.class);
        when(primary.routeCodeGenType(anyString())).thenThrow(new RuntimeException("429 主模型限流"));
        AiCodeGenTypeRoutingService fallback = mock(AiCodeGenTypeRoutingService.class);
        when(fallback.routeCodeGenType(anyString())).thenThrow(new RuntimeException("timeout 备用模型超时"));

        WorkflowContext context = run(RouterNode.create(primary, fallback),
                WorkflowContext.builder().originalPrompt("做个博客").build());

        assertEquals(CodeGenTypeEnum.HTML, context.getGenerationType());
        assertEquals(Boolean.TRUE, context.getRoutingDegraded());
        assertTrue(context.getRoutingDegradeReason().contains("429")
                        && context.getRoutingDegradeReason().contains("timeout"),
                "两个模型的失败原因都要留下，否则排查时不知道换哪家：" + context.getRoutingDegradeReason());
    }

    /**
     * 主模型正常时不能调用备用模型 —— 否则每个请求都多付一次调用
     */
    @Test
    void routerShouldNotCallFallbackWhenPrimarySucceeds() {
        AiCodeGenTypeRoutingService primary = mock(AiCodeGenTypeRoutingService.class);
        when(primary.routeCodeGenType(anyString())).thenReturn(CodeGenTypeEnum.MULTI_FILE);
        AiCodeGenTypeRoutingService fallback = mock(AiCodeGenTypeRoutingService.class);

        WorkflowContext context = run(RouterNode.create(primary, fallback),
                WorkflowContext.builder().originalPrompt("做成三个文件").build());

        assertEquals(CodeGenTypeEnum.MULTI_FILE, context.getGenerationType());
        assertNull(context.getRoutingDegraded(), "没降级就不该有标记");
        verifyNoInteractions(fallback);
    }

    // ==================== 代码生成 ====================

    /**
     * 生成目录必须和落盘时用的规则完全一致：{root}/{codeType}_{appId}，appId 取自上下文。
     */
    @Test
    void codeGeneratorShouldWriteGeneratedDirUsingContextAppId() {
        AiCodeGeneratorFacade facade = mock(AiCodeGeneratorFacade.class);
        when(facade.generateAndSaveCodeStream(anyString(), any(), any())).thenReturn(Flux.just("ok"));

        WorkflowContext context = run(CodeGeneratorNode.create(facade), WorkflowContext.builder()
                .originalPrompt("做个博客")
                .enhancedPrompt("做个博客 + 素材")
                .generationType(CodeGenTypeEnum.MULTI_FILE)
                .appId(7L)
                .build());

        // ⚠️ 目录名用的是枚举的 getValue()（小写 multi_file），不是枚举名 MULTI_FILE；
        // 必须和 CodeFileSaverTemplate#buildUniqueDir 的拼法保持一致，否则质检节点会去读一个空目录
        String expected = AppConstant.CODE_OUTPUT_ROOT_DIR + java.io.File.separator
                + CodeGenTypeEnum.MULTI_FILE.getValue() + "_7";
        assertEquals(expected, context.getGeneratedCodeDir());
        // 用的是增强提示词，不是原始需求
        verify(facade).generateAndSaveCodeStream(org.mockito.ArgumentMatchers.eq("做个博客 + 素材"),
                org.mockito.ArgumentMatchers.eq(CodeGenTypeEnum.MULTI_FILE),
                org.mockito.ArgumentMatchers.eq(7L));
        assertEquals("代码生成", context.getCurrentStep());
    }

    /**
     * 流内报错（模型超时/报错）时不能把异常抛出去，只记错并继续 —— 目录照样写进状态。
     */
    @Test
    void codeGeneratorShouldSurviveStreamError() {
        AiCodeGeneratorFacade facade = mock(AiCodeGeneratorFacade.class);
        when(facade.generateAndSaveCodeStream(anyString(), any(), any()))
                .thenReturn(Flux.error(new RuntimeException("模型超时")));

        WorkflowContext context = run(CodeGeneratorNode.create(facade), WorkflowContext.builder()
                .originalPrompt("做个博客")
                .generationType(CodeGenTypeEnum.HTML)
                .build());

        assertNotNull(context.getGeneratedCodeDir());
        assertEquals("模型超时", context.getErrorMessage());
    }

    // ==================== 代码质量检查 ====================

    /**
     * 读目录 → 拼接 → 调 AI 三步都要走通，且拼接内容必须包含真实文件、内容里不能出现 node_modules。
     */
    @Test
    void codeQualityCheckShouldReadFilesButPruneNodeModules(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("index.html"), "<html><body>hi</body></html>", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("style.css"), "body{}", StandardCharsets.UTF_8);
        // 这几个都应该被剪掉/过滤掉
        Path nodeModules = Files.createDirectories(dir.resolve("node_modules").resolve("lib"));
        Files.writeString(nodeModules.resolve("should-not-appear.js"), "NUKE_ME", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(".hidden.js"), "HIDDEN_MARK", StandardCharsets.UTF_8);

        CodeQualityCheckService service = mock(CodeQualityCheckService.class);
        when(service.checkCodeQuality(anyString()))
                .thenReturn(QualityResult.builder().isValid(true).errors(List.of()).suggestions(List.of()).build());

        WorkflowContext context = run(CodeQualityCheckNode.create(service),
                WorkflowContext.builder().generatedCodeDir(dir.toString()).build());

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(service).checkCodeQuality(captor.capture());
        String sent = captor.getValue();
        assertTrue(sent.contains("index.html"), "真实代码文件必须被拼进去");
        assertTrue(sent.contains("style.css"));
        assertFalse(sent.contains("NUKE_ME"), "node_modules 必须被整棵剪掉");
        assertFalse(sent.contains("HIDDEN_MARK"), "隐藏文件必须被跳过");

        assertEquals(true, context.getQualityResult().getIsValid());
        assertEquals("代码质量检查", context.getCurrentStep());
    }

    /**
     * 目录里没有代码文件 → 直接判失败，且<b>不调用 AI</b>（省一次费用）。
     */
    @Test
    void codeQualityCheckShouldFailFastWhenNoCodeFiles(@TempDir Path dir) {
        CodeQualityCheckService service = mock(CodeQualityCheckService.class);

        WorkflowContext context = run(CodeQualityCheckNode.create(service),
                WorkflowContext.builder().generatedCodeDir(dir.toString()).build());

        verify(service, never()).checkCodeQuality(anyString());
        assertEquals(false, context.getQualityResult().getIsValid());
        assertFalse(context.getQualityResult().getErrors().isEmpty());
    }

    /**
     * 质检服务自身异常 → 按「通过」放行，避免因为一次 AI 抖动就把整个流程卡进回退循环。
     */
    @Test
    void codeQualityCheckShouldPassThroughOnServiceException(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("index.html"), "<html></html>", StandardCharsets.UTF_8);

        CodeQualityCheckService service = mock(CodeQualityCheckService.class);
        when(service.checkCodeQuality(anyString())).thenThrow(new RuntimeException("deepseek 限流"));

        WorkflowContext context = run(CodeQualityCheckNode.create(service),
                WorkflowContext.builder().generatedCodeDir(dir.toString()).build());

        assertEquals(true, context.getQualityResult().getIsValid());
    }

    // ==================== 项目构建 ====================

    /**
     * Vue 工程：构建成功后最终目录必须是 dist。
     */
    @Test
    void projectBuilderShouldUseDistDirForVue() {
        VueProjectBuilder builder = mock(VueProjectBuilder.class);
        when(builder.buildProject(anyString())).thenReturn(true);

        WorkflowContext context = run(ProjectBuilderNode.create(builder), WorkflowContext.builder()
                .generatedCodeDir("E:/tmp/vue_project_0")
                .generationType(CodeGenTypeEnum.VUE_PROJECT)
                .build());

        assertTrue(context.getBuildResultDir().endsWith("dist"));
        assertEquals("项目构建", context.getCurrentStep());
    }

    /**
     * 构建失败要退回生成目录，而不是抛异常断掉工作流。
     */
    @Test
    void projectBuilderShouldFallbackToGeneratedDirOnFailure() {
        VueProjectBuilder builder = mock(VueProjectBuilder.class);
        when(builder.buildProject(anyString())).thenReturn(false);

        WorkflowContext context = run(ProjectBuilderNode.create(builder), WorkflowContext.builder()
                .generatedCodeDir("E:/tmp/vue_project_0")
                .generationType(CodeGenTypeEnum.VUE_PROJECT)
                .build());

        assertEquals("E:/tmp/vue_project_0", context.getBuildResultDir());
    }

    /**
     * HTML / 多文件：压根不该调用构建器。
     */
    @Test
    void projectBuilderShouldSkipBuildForNonVue() {
        VueProjectBuilder builder = mock(VueProjectBuilder.class);

        WorkflowContext context = run(ProjectBuilderNode.create(builder), WorkflowContext.builder()
                .generatedCodeDir("E:/tmp/html_0")
                .generationType(CodeGenTypeEnum.HTML)
                .build());

        verify(builder, never()).buildProject(anyString());
        assertEquals("E:/tmp/html_0", context.getBuildResultDir());
    }

    // ==================== 工具方法 ====================

    /**
     * 直接测拼接函数：不存在的目录返回空串（节点会据此判「没有可检查的文件」）。
     */
    @Test
    void readAndConcatenateShouldReturnEmptyForMissingDir() {
        assertEquals("", CodeQualityCheckNode.readAndConcatenateCodeFiles("E:/definitely/not/here"));
        assertEquals("", CodeQualityCheckNode.readAndConcatenateCodeFiles(""));
    }

    // ==================== 小工具 ====================

    /**
     * 跑一个节点：构造状态 → apply → 取回上下文。节点里的异常由节点自己吞掉，不该冒到测试里。
     */
    private static WorkflowContext run(AsyncNodeAction<MessagesState<String>> node, WorkflowContext context) {
        MessagesState<String> state = new MessagesState<>(WorkflowContext.saveContext(context));
        Map<String, Object> result = node.apply(state).join();
        // ⚠️ 不能直接把返回值强转成 WorkflowContext：状态里存的是 JSON 字符串（见 WorkflowContext#saveContext），
        // 必须走 getContext 解析回来
        WorkflowContext output = WorkflowContext.getContext(new MessagesState<>(result));
        assertNotNull(output, "节点必须返回更新后的 WorkflowContext");
        return output;
    }
}
