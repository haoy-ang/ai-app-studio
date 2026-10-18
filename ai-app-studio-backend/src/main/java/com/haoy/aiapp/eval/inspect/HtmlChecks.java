package com.haoy.aiapp.eval.inspect;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTML / 多文件模式的页面检查
 * <p>
 * 这几条检查的是「这张页面能不能正常打开」：骨架在不在、内容有没有、标签闭没闭、引用的文件找不找得到。
 * <p>
 * <b>为什么不用 HTML 解析器（jsoup）？</b>因为要解析的是「AI 可能写出了非法 HTML」的文件 ——
 * 而解析器对非法输入的处理是「尽力修复」，修复完再看结构，坏掉的标签已经被悄悄补好了，
 * 检查就永远通过。这里要的恰恰是「原始文本长什么样」，所以用正则 + 计数反而是对的。
 * <p>
 * <b>为什么标签配平用计数而不是栈？</b>因为要容忍 {@code <p>} 省略闭合这类 HTML5 合法写法，
 * 栈会判它错。所以只对「必须显式闭合」的容器标签做计数，而且先把 {@code <script>}/{@code <style>}
 * 的内部内容剥掉 —— 否则 JS 字符串里一个 {@code "</div>"} 就能让计数失真。
 *
 * @author <a>程序员haoy</a>
 */
public final class HtmlChecks {

    /**
     * 页面骨架完整
     */
    public static final ArtifactCheck SKELETON = new Skeleton();

    /**
     * 页面有实际体积（防「只有骨架的空壳」）
     */
    public static final ArtifactCheck CONTENT_SIZE = new ContentSize();

    /**
     * 容器标签开闭配平
     */
    public static final ArtifactCheck TAGS_BALANCED = new TagsBalanced();

    /**
     * body 里有肉眼可见的文字
     */
    public static final ArtifactCheck HAS_VISIBLE_CONTENT = new HasVisibleContent();

    /**
     * 引用的本地资源真实存在（多文件模式专用）
     */
    public static final ArtifactCheck ASSETS_EXIST = new AssetsExist();

    /**
     * 「必须显式闭合」的容器标签白名单
     * <p>
     * <b>刻意不含 {@code p} / {@code li} / {@code span} / {@code td} / {@code h1~h6}</b>：
     * 这些标签的结束标记在 HTML5 里可以省略，浏览器会自动补。把它们算进来，合法页面会被判失败。
     */
    private static final Set<String> BALANCED_TAGS = Set.of(
            "html", "head", "body", "div", "section", "article", "aside",
            "header", "footer", "main", "nav", "ul", "ol", "table", "form", "script", "style");

    /**
     * 资源引用：{@code src="..."} / {@code href="..."}
     * <p>
     * 前面那个 lookbehind 是为了排除 Vue 的绑定写法 {@code :src="..."} 和 {@code v-bind:href="..."} ——
     * 它们是表达式而不是文件路径，拿去检查文件存在必然误报。
     */
    private static final Pattern ASSET_REF = Pattern.compile(
            "(?<![\\w:-])(?:src|href)\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    /**
     * 外链/内联协议前缀 —— 这些不需要本地文件
     */
    private static final List<String> EXTERNAL_PREFIXES = List.of(
            "http://", "https://", "//", "data:", "#", "mailto:", "tel:", "javascript:", "blob:");

    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");

    private static final Pattern SCRIPT_BODY = Pattern.compile("(?is)<script\\b[^>]*>(.*?)</script>");

    private static final Pattern STYLE_BODY = Pattern.compile("(?is)<style\\b[^>]*>(.*?)</style>");

    private HtmlChecks() {
    }

    /**
     * 页面骨架：{@code <html>} / {@code <head>} / {@code <body>} 三段齐全
     */
    private record Skeleton() implements ArtifactCheck {

        @Override
        public String name() {
            return "html-skeleton";
        }

        @Override
        public CheckResult inspect(Path root, ArtifactInspector.Options options) {
            Path index = root.resolve("index.html");
            if (!Files.isRegularFile(index)) {
                return CheckResult.fail(name(), "缺少入口文件 index.html");
            }
            String content = ArtifactFiles.read(index).toLowerCase();
            List<String> missing = new ArrayList<>();
            if (!content.contains("<html")) {
                missing.add("<html");
            }
            if (!content.contains("<head")) {
                missing.add("<head");
            }
            if (!content.contains("<body")) {
                missing.add("<body");
            }
            if (missing.isEmpty()) {
                return CheckResult.pass(name(), "html/head/body 齐全");
            }
            return CheckResult.fail(name(), "缺少标签: " + String.join(", ", missing));
        }
    }

    /**
     * 入口文件体积
     * <p>
     * 阈值 512 字节定得有意偏低：一个「有导航、有标题、有内容」的页面不可能小于这个数，
     * 而任何一步生成失败（流中断、只写了个头）都会落在这个数以下。
     * 它抓的是「明显没写完」，不是「写得不够好」—— 后者交给 L2 的语义评估。
     */
    private record ContentSize() implements ArtifactCheck {

        private static final long MIN_BYTES = 512;

        @Override
        public String name() {
            return "html-content-size";
        }

        @Override
        public CheckResult inspect(Path root, ArtifactInspector.Options options) {
            Path index = root.resolve("index.html");
            if (!Files.isRegularFile(index)) {
                return CheckResult.skip(name(), "缺少 index.html");
            }
            try {
                long size = Files.size(index);
                if (size >= MIN_BYTES) {
                    return CheckResult.pass(name(), size + " 字节");
                }
                return CheckResult.fail(name(), "index.html 只有 " + size + " 字节（阈值 " + MIN_BYTES + "），疑似生成中断");
            } catch (Exception e) {
                return CheckResult.fail(name(), "读取文件大小失败: " + e.getMessage());
            }
        }
    }

    /**
     * 容器标签配平
     */
    private record TagsBalanced() implements ArtifactCheck {

        @Override
        public String name() {
            return "html-tags-balanced";
        }

        @Override
        public CheckResult inspect(Path root, ArtifactInspector.Options options) {
            Path index = root.resolve("index.html");
            if (!Files.isRegularFile(index)) {
                return CheckResult.skip(name(), "缺少 index.html");
            }
            // 超限时内容是截断的，配平结论不可信 —— 宁可跳过，也不要误报
            if (ArtifactFiles.exceedsReadLimit(index)) {
                return CheckResult.skip(name(), "index.html 超过 2MB，截断后无法可靠判断标签配平");
            }
            String content = stripNoise(ArtifactFiles.read(index));

            Map<String, Integer> unbalanced = new LinkedHashMap<>();
            for (String tag : BALANCED_TAGS) {
                int open = countMatches(content, Pattern.compile("<" + tag + "\\b", Pattern.CASE_INSENSITIVE));
                int close = countMatches(content, Pattern.compile("</" + tag + "\\s*>", Pattern.CASE_INSENSITIVE));
                if (open != close) {
                    unbalanced.put(tag, open - close);
                }
            }
            if (unbalanced.isEmpty()) {
                return CheckResult.pass(name(), "容器标签开闭一致");
            }
            List<String> detail = unbalanced.entrySet().stream()
                    .map(e -> "<" + e.getKey() + "> 差 " + e.getValue() + " 个")
                    .toList();
            return CheckResult.fail(name(), String.join("; ", detail));
        }
    }

    /**
     * body 里有可见文字
     * <p>
     * 判据是「剥掉标签后剩下的非空白字符数」，不是「有 div」—— 因为 AI 最常见的失败之一是
     * 写了一个完整的布局骨架、但所有文案都是空的 {@code <h1></h1>}，打开是一片白。
     */
    private record HasVisibleContent() implements ArtifactCheck {

        private static final int MIN_VISIBLE_CHARS = 50;

        @Override
        public String name() {
            return "html-has-visible-content";
        }

        @Override
        public CheckResult inspect(Path root, ArtifactInspector.Options options) {
            Path index = root.resolve("index.html");
            if (!Files.isRegularFile(index)) {
                return CheckResult.skip(name(), "缺少 index.html");
            }
            // 截断会让可见文本变少，可能把「内容很多的大页面」误判成空壳
            if (ArtifactFiles.exceedsReadLimit(index)) {
                return CheckResult.skip(name(), "index.html 超过 2MB，截断后无法可靠统计可见文本");
            }
            String content = ArtifactFiles.read(index);
            String visible = stripNoise(content)
                    .replaceAll("(?s)<[^>]*>", " ")
                    .replaceAll("&[a-zA-Z#0-9]+;", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
            if (visible.length() >= MIN_VISIBLE_CHARS) {
                return CheckResult.pass(name(), "可见文本 " + visible.length() + " 字符");
            }
            return CheckResult.fail(name(),
                    "可见文本仅 " + visible.length() + " 字符（阈值 " + MIN_VISIBLE_CHARS + "），页面可能是空壳");
        }
    }

    /**
     * 本地资源引用可达
     */
    private record AssetsExist() implements ArtifactCheck {

        @Override
        public String name() {
            return "html-assets-exist";
        }

        @Override
        public CheckResult inspect(Path root, ArtifactInspector.Options options) {
            Path index = root.resolve("index.html");
            if (!Files.isRegularFile(index)) {
                return CheckResult.skip(name(), "缺少 index.html");
            }
            Matcher matcher = ASSET_REF.matcher(ArtifactFiles.read(index));
            List<String> missing = new ArrayList<>();
            int checked = 0;
            while (matcher.find()) {
                String ref = matcher.group(1).trim();
                if (isExternal(ref) || isDynamic(ref)) {
                    continue;
                }
                checked++;
                Path target = root.resolve(stripQuery(ref)).normalize();
                if (!Files.exists(target)) {
                    missing.add(ref);
                }
            }
            if (checked == 0) {
                return CheckResult.skip(name(), "没有本地资源引用");
            }
            if (missing.isEmpty()) {
                return CheckResult.pass(name(), checked + " 个本地引用全部存在");
            }
            return CheckResult.fail(name(), "引用的文件不存在: " + String.join(", ", missing));
        }
    }

    /**
     * 去掉注释与 script/style 的内部内容（保留标签本身），再做结构分析
     * <p>
     * <b>不剥这两段会怎样</b>：AI 在 JS 里写 {@code innerHTML = "<div>...</div>"} 是常态，
     * 里面的 div 会污染计数；CSS 里的选择器也可能出现 {@code body {} } 之类字样。
     */
    private static String stripNoise(String html) {
        String noComment = COMMENT.matcher(html).replaceAll(" ");
        String noScript = SCRIPT_BODY.matcher(noComment).replaceAll("<script></script>");
        return STYLE_BODY.matcher(noScript).replaceAll("<style></style>");
    }

    private static int countMatches(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static boolean isExternal(String ref) {
        String lower = ref.toLowerCase();
        return EXTERNAL_PREFIXES.stream().anyMatch(lower::startsWith);
    }

    /**
     * 模板表达式形式的路径（{@code {{ url }}} / {@code ${url}}）跳过 —— 它不是字面量，没法判存在
     */
    private static boolean isDynamic(String ref) {
        return ref.contains("{{") || ref.contains("${");
    }

    private static String stripQuery(String ref) {
        int cut = ref.length();
        int q = ref.indexOf('?');
        int h = ref.indexOf('#');
        if (q >= 0) {
            cut = Math.min(cut, q);
        }
        if (h >= 0) {
            cut = Math.min(cut, h);
        }
        return ref.substring(0, cut);
    }
}
