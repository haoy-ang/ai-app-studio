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
