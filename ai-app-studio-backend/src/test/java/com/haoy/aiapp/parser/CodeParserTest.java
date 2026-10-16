package com.haoy.aiapp.parser;

import com.haoy.aiapp.ai.model.HtmlCodeResult;
import com.haoy.aiapp.ai.model.MultiFileCodeResult;
import com.haoy.aiapp.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * 代码解析器测试
 * <p>
 * 纯逻辑测试，不启动 Spring 容器，秒级跑完，适合每次改动正则后回归。
 *
 * @author <a>程序员haoy</a>
 */
class CodeParserTest {

    @Test
    void parseHtmlCode() {
        String codeContent = """
                随便写一段描述：
                ```html
                <!DOCTYPE html>
                <html lang="zh">
                <head>
                <title>测试页面</title>
                </head>
                <body>
                <h1>Hello World!</h1>
                </body>
                </html>
                ```
                随便写一段描述
                """;
        HtmlCodeResult result = (HtmlCodeResult) CodeParserExecutor.executeParser(codeContent, CodeGenTypeEnum.HTML);
        Assertions.assertNotNull(result);
        Assertions.assertNotNull(result.getHtmlCode());
        // 代码块以外的描述文字不应该被当成代码收进来
        Assertions.assertTrue(result.getHtmlCode().contains("Hello World!"));
        Assertions.assertFalse(result.getHtmlCode().contains("随便写一段描述"));
    }

    @Test
    void parseMultiFileCode() {
        String codeContent = """
                创建一个完整的网页：
                ```html
                <!DOCTYPE html>
                <html lang="zh">
                <head>
                <title>多文件示例</title>
                <link rel="stylesheet" href="style.css">
                </head>
                <body>
                <h1>欢迎使用</h1>
                <script src="script.js"></script>
                </body>
                </html>
                ```
                ```css
                h1 {
                    color: blue;
                    text-align: center;
                }
                ```
                ```js
                console.log('页面加载完成');
                ```
                文件创建完成！
                """;
        MultiFileCodeResult result = (MultiFileCodeResult) CodeParserExecutor.executeParser(codeContent,
                CodeGenTypeEnum.MULTI_FILE);
        Assertions.assertNotNull(result);
        Assertions.assertNotNull(result.getHtmlCode());
        Assertions.assertNotNull(result.getCssCode());
        Assertions.assertNotNull(result.getJsCode());
        Assertions.assertTrue(result.getCssCode().contains("color: blue"));
        Assertions.assertTrue(result.getJsCode().contains("页面加载完成"));
    }
}
