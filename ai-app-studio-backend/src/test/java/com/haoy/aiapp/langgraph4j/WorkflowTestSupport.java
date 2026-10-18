package com.haoy.aiapp.langgraph4j;

import cn.hutool.core.io.FileUtil;
import com.haoy.aiapp.ai.AiCodeGenTypeRoutingService;
import com.haoy.aiapp.constant.AppConstant;
import com.haoy.aiapp.core.AiCodeGeneratorFacade;
import com.haoy.aiapp.langgraph4j.ai.CodeQualityCheckService;
import com.haoy.aiapp.langgraph4j.ai.ImageCollectionService;
import com.haoy.aiapp.langgraph4j.model.QualityResult;
import com.haoy.aiapp.model.enums.CodeGenTypeEnum;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工作流测试公共桩 —— 把「假依赖」集中在一处，让 P5~P8 各自只关心自己的断言。
 * <p>
 * 为什么值得专门抽一个类：接了 P6 的质检节点之后，一个「假 facade」不再只是返回空流那么简单 ——
 * 质检节点会去<b>读生成目录里的真实文件</b>，所以假 facade 必须真的把文件落盘，
 * 否则质检会判「没有可检查的代码」、把工作流拽进回退循环，测试就失真了。
 * 这套「会落盘的 facade + 两种质检结果 + 路由桩」在多个测试里都要用，抽出来避免三份复制粘贴。
 *
 * @author <a>程序员haoy</a>
 */
final class WorkflowTestSupport {

    /**
     * 网上那两张图片的假文本形式（工具调用型收集的返回）
     */
    static final String IMAGE_TEXT = "{\"content\":[{\"url\":\"https://x/1.png\"}]}";

    /**
     * 生成目录里写进去的那段 HTML —— 质检节点要读到它才会判「有可检查的代码」
     */
    static final String FAKE_HTML = "<html><body>ok</body></html>";

    private WorkflowTestSupport() {
    }

    /**
     * 按 {@code {CODE_OUTPUT_ROOT_DIR}/{codeType}_{appId}} 规则算出生成目录（与落盘代码一致）
     */
    static Path codeDirOf(CodeGenTypeEnum type, Long appId) {
        return Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, type.getValue() + "_" + appId);
    }

    /**
     * 一个「还会真落盘」的假 facade
     * <p>
     * {@code thenAnswer} 里按调用参数算出目录、往里写一个 {@code index.html}，
     * 这样下游质检节点才能读到文件、判「有可检查的代码」。测试跑完记得调 {@link #deleteDirs}。
     */
    static AiCodeGeneratorFacade facadeWritingCode() {
        AiCodeGeneratorFacade facade = mock(AiCodeGeneratorFacade.class);
        when(facade.generateAndSaveCodeStream(anyString(), any(), any(), any())).thenAnswer(invocation -> {
            CodeGenTypeEnum type = invocation.getArgument(1);
            Long appId = invocation.getArgument(2);
            Path dir = codeDirOf(type, appId);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("index.html"), FAKE_HTML, StandardCharsets.UTF_8);
            return Flux.just("ok");
        });
        return facade;
    }

    /**
     * 质检永远通过
     */
    static CodeQualityCheckService qualityAlwaysPass() {
        CodeQualityCheckService service = mock(CodeQualityCheckService.class);
        when(service.checkCodeQuality(anyString()))
                .thenReturn(QualityResult.builder().isValid(true).errors(List.of()).suggestions(List.of()).build());
        return service;
    }

    /**
     * 质检永远失败 —— 且<b>带上了错误项</b>，这样才会触发「返工」而不是被当成「通过」
     */
    static CodeQualityCheckService qualityAlwaysFail() {
        CodeQualityCheckService service = mock(CodeQualityCheckService.class);
        when(service.checkCodeQuality(anyString())).thenReturn(QualityResult.builder()
                .isValid(false)
                .errors(List.of("存在语法错误，页面无法渲染"))
                .suggestions(List.of("修正 JS 语法"))
                .build());
        return service;
    }

    /**
     * 路由桩：无论需求是什么都返回指定类型
     */
    static AiCodeGenTypeRoutingService routing(CodeGenTypeEnum type) {
        AiCodeGenTypeRoutingService service = mock(AiCodeGenTypeRoutingService.class);
        when(service.routeCodeGenType(anyString())).thenReturn(type);
        return service;
    }

    /**
     * 图片收集桩（工具调用型）
     */
    static ImageCollectionService imageService() {
        return prompt -> IMAGE_TEXT;
    }

    /**
     * 删除本测试专用的生成目录，避免污染真实的 {@code html_0} / {@code vue_project_0} 产物
     *
     * @param dirNames 目录名，如 {@code html_990001}
     */
    static void deleteDirs(String... dirNames) {
        for (String name : dirNames) {
            FileUtil.del(Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, name).toFile());
        }
    }
}
