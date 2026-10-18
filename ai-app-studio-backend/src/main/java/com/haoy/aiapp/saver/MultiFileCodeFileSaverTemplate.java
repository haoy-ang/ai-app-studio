package com.haoy.aiapp.saver;

import cn.hutool.core.util.StrUtil;
import com.haoy.aiapp.ai.model.MultiFileCodeResult;
import com.haoy.aiapp.exception.BusinessException;
import com.haoy.aiapp.exception.ErrorCode;
import com.haoy.aiapp.model.enums.CodeGenTypeEnum;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多文件代码保存器
 * <p>
 * <b>它解决的问题：拿到三个代码块（html/css/js）之后，怎么把它们落成「能打开的页面」。</b>
 * 听起来是复制粘贴，但真正的难点在文件名：<b>html 里引用的是什么名字，文件就得叫什么名字。</b>
 * <p>
 * 提示词里写死了 {@code style.css} / {@code script.js}，模型绝大多数时候会遵守。
 * 但「绝大多数」不是「一定」——曾经的实现用
 * {@code htmlCode.contains("style.css")} 判断「模型有没有外链样式」，再用硬编码的
 * {@code style.css} 写文件，两个地方各自假设了一次文件名。一旦模型把外链写成
 * {@code main.css}：
 * <ul>
 *   <li>{@code contains("style.css")} 为 false → 校验放行，哪怕 cssCode 是空的；</li>
 *   <li>写文件仍然写 {@code style.css} → html 引用的 {@code main.css} 404。</li>
 * </ul>
 * 结果就是「生成成功、页面没样式」这种最难排查的坏结果。
 * <p>
 * <b>所以这里改成「以 html 的实际引用为准」：</b>先从 html 里解析出 {@code <link href>} 和
 * {@code <script src>} 指向的本地路径，再照着这个路径落盘。解析不到（没外链、或外链是 CDN）
 * 时才回落到约定的默认名。校验和写入共用同一份解析结果，两处不可能再对不上。
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
public class MultiFileCodeFileSaverTemplate extends CodeFileSaverTemplate<MultiFileCodeResult> {

    /**
     * 提示词约定的默认文件名 —— 只在 html 里解析不到本地引用时使用
     */
    private static final String DEFAULT_CSS_NAME = "style.css";

    private static final String DEFAULT_JS_NAME = "script.js";

    /**
     * 匹配 {@code <link href="xxx.css">}，允许 href 前后有别的属性、允许带查询串
     */
    private static final Pattern CSS_REF = Pattern.compile(
            "<link\\b[^>]*?\\bhref\\s*=\\s*[\"']([^\"']+?\\.css(?:\\?[^\"']*)?)[\"']",
            Pattern.CASE_INSENSITIVE);

    /**
     * 匹配 {@code <script src="xxx.js">}
     */
    private static final Pattern JS_REF = Pattern.compile(
            "<script\\b[^>]*?\\bsrc\\s*=\\s*[\"']([^\"']+?\\.js(?:\\?[^\"']*)?)[\"']",
            Pattern.CASE_INSENSITIVE);

    @Override
    protected CodeGenTypeEnum getCodeType() {
        return CodeGenTypeEnum.MULTI_FILE;
    }

    @Override
    protected void validateInput(MultiFileCodeResult result) {
        super.validateInput(result);
        // 至少要有 HTML 代码，CSS 和 JS 可以为空
        String htmlCode = result.getHtmlCode();
        if (StrUtil.isBlank(htmlCode)) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "HTML 代码内容不能为空");
        }

        // 校验的依据是「html 真的引用了什么」，而不是「html 里有没有出现 style.css 这个字符串」。
        // 实测模型偶尔只吐一个 html 代码块，此时 writeToFile 会静默跳过空内容：
        // 页面上线了却没有样式、没有交互，html 里还挂着 404 的外链 —— 不如直接抛错让用户重试。
        List<String> cssRefs = findLocalRefs(htmlCode, CSS_REF);
        if (StrUtil.isBlank(result.getCssCode()) && !cssRefs.isEmpty()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "多文件生成不完整：index.html 引用了 " + String.join(" / ", cssRefs)
                            + "，但模型没有返回 CSS 代码块，请重试");
        }
        List<String> jsRefs = findLocalRefs(htmlCode, JS_REF);
        if (StrUtil.isBlank(result.getJsCode()) && !jsRefs.isEmpty()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "多文件生成不完整：index.html 引用了 " + String.join(" / ", jsRefs)
                            + "，但模型没有返回 JS 代码块，请重试");
        }
    }

    @Override
    protected void saveFiles(MultiFileCodeResult result, String baseDirPath) {
        String htmlCode = result.getHtmlCode();

        String cssName = pickFileName(htmlCode, CSS_REF, DEFAULT_CSS_NAME);
        String jsName = pickFileName(htmlCode, JS_REF, DEFAULT_JS_NAME);

        writeToFile(baseDirPath, "index.html", htmlCode);
        writeToFile(baseDirPath, cssName, result.getCssCode());
        writeToFile(baseDirPath, jsName, result.getJsCode());

        // 一个 cssCode 只能对一个文件：模型若引用了多个样式表，多出来的那几个注定 404。
        // 不为此报错（页面仍可用），但必须留痕 —— 否则没人知道那个 404 是谁造成的。
        warnAboutExtraRefs(htmlCode, CSS_REF, cssName);
        warnAboutExtraRefs(htmlCode, JS_REF, jsName);
    }

    /**
     * 决定代码块该写成什么文件名
     * <p>
     * 优先跟着 html 的引用走（这样页面一定能加载到），解析不到才用提示词约定的默认名。
     *
     * @param htmlCode   html 内容
     * @param pattern    引用匹配规则
     * @param defaultName 默认文件名
     * @return 落盘用的文件名（可能是 {@code css/main.css} 这种带子目录的相对路径）
     */
    private String pickFileName(String htmlCode, Pattern pattern, String defaultName) {
        List<String> refs = findLocalRefs(htmlCode, pattern);
        if (refs.isEmpty()) {
            return defaultName;
        }
        if (refs.size() > 1) {
            log.debug("html 引用了多个同类资源，代码块只能对第一个落盘: {}", refs);
        }
        return refs.get(0);
    }

    /**
     * 引用多于一个时留一条日志
     * <p>
     * 用 {@code warn} 而不是 {@code error}：页面本身还能用，只是少了几个样式表 / 脚本，
     * 属于「该知道但不该告警」的级别。
     */
    private void warnAboutExtraRefs(String htmlCode, Pattern pattern, String usedName) {
        List<String> refs = findLocalRefs(htmlCode, pattern);
        if (refs.size() > 1) {
            log.warn("index.html 引用了 {} 个同类资源 {}，但只有一份代码块，已写入 {}，其余引用会 404",
                    refs.size(), refs, usedName);
        }
    }

    /**
     * 解析 html 里指向本地文件的引用
     * <p>
     * 会过滤掉三类不该管的：
     * <ul>
     *   <li><b>外链</b>（{@code http(s)://}、{@code //cdn...}、{@code data:}）—— 那些文件不由我们生成；</li>
     *   <li><b>绝对路径</b>（以 {@code /} 开头）—— 它的根是部署服务器，不是项目目录；</li>
     *   <li><b>带 {@code ..} 的路径</b> —— 一个由模型产出的字符串不该有写穿目录的能力。</li>
     * </ul>
     * 查询串（{@code style.css?v=2}）和片段（{@code #x}）会去掉：它们是浏览器用的，不是文件名。
     *
     * @param htmlCode html 内容
     * @param pattern  引用匹配规则
     * @return 去重后的相对路径列表；解析不到时为空列表
     */
    private static List<String> findLocalRefs(String htmlCode, Pattern pattern) {
        if (StrUtil.isBlank(htmlCode)) {
            return List.of();
        }
        List<String> refs = new ArrayList<>();
        Matcher matcher = pattern.matcher(htmlCode);
        while (matcher.find()) {
            String path = normalizeLocalPath(matcher.group(1));
            if (path != null && !refs.contains(path)) {
                refs.add(path);
            }
        }
        return refs;
    }

    /**
     * 把引用路径规范化成「可以直接用于落盘的相对路径」
     *
     * @param raw html 里原样的引用
     * @return 可用的相对路径；不该由我们落盘时返回 null
     */
    private static String normalizeLocalPath(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        String path = raw.trim();

        int fragment = path.indexOf('#');
        if (fragment >= 0) {
            path = path.substring(0, fragment);
        }
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        if (path.isEmpty()) {
            return null;
        }

        String lower = path.toLowerCase();
        if (lower.startsWith("http:") || lower.startsWith("https:") || lower.startsWith("//")
                || lower.startsWith("data:")) {
            return null;
        }
        if (path.startsWith("/") || path.startsWith("\\") || path.contains("..")) {
            return null;
        }
        return path.replace('\\', '/');
    }
}
