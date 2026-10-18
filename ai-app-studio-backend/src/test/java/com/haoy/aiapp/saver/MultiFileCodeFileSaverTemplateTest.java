package com.haoy.aiapp.saver;

import cn.hutool.core.io.FileUtil;
import com.haoy.aiapp.ai.model.MultiFileCodeResult;
import com.haoy.aiapp.constant.AppConstant;
import com.haoy.aiapp.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多文件代码保存器测试
 * <p>
 * 覆盖的是「模型不听话」时的兜底：提示词要求 index.html 用
 * {@code <link rel="stylesheet" href="style.css">} 和 {@code <script src="script.js">}
 * 外链样式和脚本，但模型偶尔只吐一个 html 代码块。
 * <p>
 * 此时原实现会「静默跳过空内容」，结果是页面能打开、但没有样式、没有交互，
 * 而且 html 里挂着两个 404 的外链 —— 用户只看到「生成成功」，实际问题被藏起来了。
 * 所以这里钉住：要么三个文件都落盘，要么直接报错。
 *
 * @author <a>程序员haoy</a>
 */
class MultiFileCodeFileSaverTemplateTest {

    /**
     * 专供测试使用的 appId，避开真实应用的目录
     */
    private static final long TEST_APP_ID = 990000002L;

    private final MultiFileCodeFileSaverTemplate saver = new MultiFileCodeFileSaverTemplate();

    private File projectRoot;

    @BeforeEach
    void setUp() {
        projectRoot = Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR,
                "multi_file_" + TEST_APP_ID).toFile();
        FileUtil.del(projectRoot);
    }

    @AfterEach
    void tearDown() {
        // 只删本次测试造出来的那个项目目录
        FileUtil.del(projectRoot);
    }

    @Test
    @DisplayName("三个代码块齐全时，index.html / style.css / script.js 全部落盘")
    void testSaveComplete() {
        File dir = saver.saveCode(buildCompleteResult(), TEST_APP_ID);

        Path base = dir.toPath();
        assertTrue(Files.exists(base.resolve("index.html")), "index.html 应存在");
        assertTrue(Files.exists(base.resolve("style.css")), "style.css 应存在");
        assertTrue(Files.exists(base.resolve("script.js")), "script.js 应存在");
    }

    @Test
    @DisplayName("模型只吐 html 却外链 style.css：拒绝保存，并说明缺了什么")
    void testRejectMissingCss() {
        MultiFileCodeResult result = new MultiFileCodeResult();
        result.setHtmlCode("<html><head><link rel=\"stylesheet\" href=\"style.css\"></head>"
                + "<body><h1>首页</h1></body></html>");
        result.setJsCode("console.log('ok')");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> saver.saveCode(result, TEST_APP_ID), "缺少 CSS 代码块时必须报错");
        assertTrue(ex.getMessage().contains("style.css"),
                "错误信息要指出缺的是 style.css，实际: " + ex.getMessage());
        assertFalse(projectRoot.exists(), "校验不通过时不应落盘任何文件");
    }

    @Test
    @DisplayName("模型只吐 html 却外链 script.js：同样拒绝保存")
    void testRejectMissingJs() {
        MultiFileCodeResult result = new MultiFileCodeResult();
        result.setHtmlCode("<html><head><link rel=\"stylesheet\" href=\"style.css\"></head>"
                + "<body><script src=\"script.js\"></script></body></html>");
        result.setCssCode("body { margin: 0; }");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> saver.saveCode(result, TEST_APP_ID), "缺少 JS 代码块时必须报错");
        assertTrue(ex.getMessage().contains("script.js"),
                "错误信息要指出缺的是 script.js，实际: " + ex.getMessage());
        assertFalse(projectRoot.exists());
    }

    @Test
    @DisplayName("html 里没有外链（样式脚本都内联）时，允许 CSS / JS 为空")
    void testAllowInlineHtmlWithoutExternalRefs() {
        MultiFileCodeResult result = new MultiFileCodeResult();
        result.setHtmlCode("<html><head><style>body{margin:0}</style></head>"
                + "<body><script>console.log(1)</script></body></html>");

        File dir = saver.saveCode(result, TEST_APP_ID);

        assertTrue(Files.exists(dir.toPath().resolve("index.html")));
        assertFalse(Files.exists(dir.toPath().resolve("style.css")),
                "没有代码块就不该凭空造文件");
    }

    @Test
    @DisplayName("HTML 代码块本身缺失时直接报错")
    void testRejectBlankHtml() {
        MultiFileCodeResult result = new MultiFileCodeResult();
        result.setCssCode("body { margin: 0; }");

        assertThrows(BusinessException.class, () -> saver.saveCode(result, TEST_APP_ID));
        assertFalse(projectRoot.exists());
    }

    // ==================== 文件名不能再靠硬编码假设 ====================

    @Test
    @DisplayName("模型把外链写成 main.css：样式必须落到 main.css，否则页面照样没样式")
    void testHonoursCustomCssFileName() {
        MultiFileCodeResult result = new MultiFileCodeResult();
        result.setHtmlCode("""
                <html><head><link rel="stylesheet" href="main.css"></head>
                <body><h1>首页</h1><script src="script.js"></script></body></html>
                """);
        result.setCssCode("body { margin: 0; }");
        result.setJsCode("console.log('ok')");

        File dir = saver.saveCode(result, TEST_APP_ID);
        Path base = dir.toPath();

        // 这一条是本次修的核心：写死 style.css 的话，html 引用的 main.css 就是 404
        assertTrue(Files.exists(base.resolve("main.css")), "样式必须落在 html 实际引用的 main.css 上");
        assertFalse(Files.exists(base.resolve("style.css")),
                "既然 html 没引用 style.css，就不该多产出这个文件");
        assertTrue(Files.exists(base.resolve("script.js")));
    }

    @Test
    @DisplayName("外链在子目录（css/main.css）时要自动建目录，不能因为目录不存在就丢文件")
    void testSupportsSubDirectoryRef() {
        MultiFileCodeResult result = new MultiFileCodeResult();
        result.setHtmlCode("""
                <html><head><link rel="stylesheet" href="css/main.css"></head>
                <body><h1>首页</h1></body></html>
                """);
        result.setCssCode("body { margin: 0; }");

        File dir = saver.saveCode(result, TEST_APP_ID);

        assertTrue(Files.exists(dir.toPath().resolve("css/main.css")),
                "带子目录的引用要连目录一起建出来");
    }

    @Test
    @DisplayName("外链带查询串 style.css?v=2：仍能认出来，文件名不能变成 style.css?v=2")
    void testStripsQueryString() {
        MultiFileCodeResult result = new MultiFileCodeResult();
        result.setHtmlCode("""
                <html><head><link rel="stylesheet" href="style.css?v=2"></head>
                <body><h1>首页</h1></body></html>
                """);

        // CSS 代码块为空 + 确实引用了 style.css → 必须报错（查询串不能把校验绕过去）
        BusinessException ex = assertThrows(BusinessException.class,
                () -> saver.saveCode(result, TEST_APP_ID));
        assertTrue(ex.getMessage().contains("style.css"), "错误信息要指出缺的是 style.css：" + ex.getMessage());

        result.setCssCode("body { margin: 0; }");
        File dir = saver.saveCode(result, TEST_APP_ID);
        assertTrue(Files.exists(dir.toPath().resolve("style.css")), "查询串不该混进文件名");
    }

    @Test
    @DisplayName("外链指向 CDN 时不要求本地 CSS 代码块 —— 那是别人的文件，不该我们生成")
    void testIgnoresExternalCdnRefs() {
        MultiFileCodeResult result = new MultiFileCodeResult();
        result.setHtmlCode("""
                <html><head>
                  <link rel="stylesheet" href="https://cdn.example.com/reset.css">
                </head><body><h1>首页</h1></body></html>
                """);

        File dir = saver.saveCode(result, TEST_APP_ID);

        assertTrue(Files.exists(dir.toPath().resolve("index.html")));
        assertFalse(Files.exists(dir.toPath().resolve("style.css")), "不该凭空造文件");
    }

    @Test
    @DisplayName("引用带 ../ 的路径：不跟着写出去（防目录穿越），回落到默认名")
    void testRejectsPathTraversal() {
        MultiFileCodeResult result = new MultiFileCodeResult();
        result.setHtmlCode("""
                <html><head><link rel="stylesheet" href="../../evil.css"></head>
                <body><h1>首页</h1></body></html>
                """);
        result.setCssCode("body { margin: 0; }");

        File dir = saver.saveCode(result, TEST_APP_ID);
        Path base = dir.toPath();

        assertTrue(Files.exists(base.resolve("style.css")), "解析不出安全路径时应回落到默认名");
        assertFalse(Files.exists(base.getParent().getParent().resolve("evil.css")),
                "模型给的字符串不该有写穿项目目录的能力");
    }

    /**
     * 一次「模型完全遵守格式」的正常结果
     */
    private MultiFileCodeResult buildCompleteResult() {
        MultiFileCodeResult result = new MultiFileCodeResult();
        result.setHtmlCode("<html><head><link rel=\"stylesheet\" href=\"style.css\"></head>"
                + "<body><h1>首页</h1><script src=\"script.js\"></script></body></html>");
        result.setCssCode("body { margin: 0; }");
        result.setJsCode("console.log('ok')");
        return result;
    }
}
