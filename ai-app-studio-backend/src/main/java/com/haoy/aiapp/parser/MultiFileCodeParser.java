package com.haoy.aiapp.parser;

import com.haoy.aiapp.ai.model.MultiFileCodeResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多文件代码解析器（HTML + CSS + JS）
 */
public class MultiFileCodeParser implements CodeParser<MultiFileCodeResult> {

    /** 匹配 ```html ... ``` 代码块 */
    private static final Pattern HTML_CODE_PATTERN = Pattern.compile(
            "```html\\s*\\n([\\s\\S]*?)\\n?```",
            Pattern.CASE_INSENSITIVE
    );

    /** 匹配 ```css ... ``` 代码块 */
    private static final Pattern CSS_CODE_PATTERN = Pattern.compile(
            "```css\\s*\\n([\\s\\S]*?)\\n?```",
            Pattern.CASE_INSENSITIVE
    );

    /** 匹配 ```js 或 ```javascript ... ``` 代码块 */
    private static final Pattern JS_CODE_PATTERN = Pattern.compile(
            "```(?:js|javascript)\\s*\\n([\\s\\S]*?)\\n?```",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public MultiFileCodeResult parseCode(String codeContent) {
        MultiFileCodeResult result = new MultiFileCodeResult();

        String htmlCode = extractCodeByPattern(codeContent, HTML_CODE_PATTERN);
        String cssCode = extractCodeByPattern(codeContent, CSS_CODE_PATTERN);
        String jsCode = extractCodeByPattern(codeContent, JS_CODE_PATTERN);

        if (htmlCode != null && !htmlCode.trim().isEmpty()) {
            result.setHtmlCode(htmlCode.trim());
        }
        if (cssCode != null && !cssCode.trim().isEmpty()) {
            result.setCssCode(cssCode.trim());
        }
        if (jsCode != null && !jsCode.trim().isEmpty()) {
            result.setJsCode(jsCode.trim());
        }
        return result;
    }

    /**
     * 根据正则模式提取代码
     */
    private String extractCodeByPattern(String content, Pattern pattern) {
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}