package com.haoy.aiapp.eval.inspect;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 与生成类型无关的通用检查
 * <p>
 * 这几条的共同点：<b>它们判断的是「AI 有没有偷懒或写飞」，跟产物是单页还是工程无关</b>。
 * 所以它们对三种类型都跑，是其余检查的地基 —— 地基塌了，上面的骨架检查没意义。
 * <p>
 * <b>关于 {@code no-template-residue} 里为什么只查 {@code {{}} 不查 {@code ${}}</b>：
 * {@code ${}} 在 HTML 的内联 {@code <script>} 里是合法的模板字符串写法，查它必然误报；
 * 而 {@code {{}}} 在纯 HTML 里没有任何语义（那是 Vue 的插值语法），出现就说明 AI 把 Vue 模板
 * 塞进了静态页，或者把占位符忘了替换。所以前者不查、后者按硬检查算。
 * <p>
 * 同理，{@code {{}}} 只对 {@code .html} 文件查 —— {@code .vue} 里它就是正常语法。
 *
 * @author <a>程序员haoy</a>
 */
public final class CommonChecks {

    /**
     * 目录存在且里面真的有文件
     */
    public static final ArtifactCheck ROOT_EXISTS = new RootExists();

    /**
     * 没有「忘了替换」的残留：TODO / FIXME / lorem ipsum / HTML 里的 mustache
     */
    public static final ArtifactCheck NO_TEMPLATE_RESIDUE = new NoTemplateResidue();

    /**
     * 页面上没有把 {@code undefined} / {@code null} 直接渲染出来
     */
    public static final ArtifactCheck NO_BROKEN_VALUE = new NoBrokenValue();

    /**
     * 没有用第三方占位图服务（软检查：能用，但说明素材没准备）
     */
    public static final ArtifactCheck NO_PLACEHOLDER_SERVICE = new NoPlaceholderService();

    /**
     * 「忘了做完」的标记
     * <p>
     * <b>为什么对大小写敏感？</b>这一条是拿真实产物扫出来的教训：
     * 一个「待办清单」应用里到处是 {@code TodoInput.vue}、{@code "todo-app"}、{@code todo list}，
     * 用大小写不敏感匹配，整个应用会被判成「全是残留标记」—— 假阳性。
     * 而「待办标记」的行业惯例就是全大写（{@code TODO} / {@code FIXME}），
     * 产品名几乎不会全大写。所以这里只认全大写。
     * <p>
     * 代价是漏掉写成小写 {@code // todo: xxx} 的 AI 输出。这个取舍是有意的：
     * <b>漏报只是少发现一个问题，误报会让人不再相信报告。</b>
     */
    private static final Pattern LAZY_MARK = Pattern.compile("\\bTODO\\b|\\bFIXME\\b");

    /**
     * Lorem ipsum 占位文案（这个不分大小写，它本来就是拉丁文填充词，没有业务同名风险）
     */
    private static final Pattern LOREM = Pattern.compile("lorem\\s+ipsum", Pattern.CASE_INSENSITIVE);

    private static final Pattern MUSTACHE = Pattern.compile("\\{\\{");

    private static final Pattern BROKEN_VALUE = Pattern.compile(">\\s*(undefined|null|NaN)\\s*<");

    /**
     * 占位图服务的域名
     * <p>
     * 这一条<b>刻意保持软检查</b>：用了占位图，页面照样能打开、能看，它不是「坏」。
     * 但它是一个<b>信号</b>——说明图片收集环节没起作用（或者 AI 偷懒），
     * 而「素材是不是真的」恰好是这个项目和别的 AI 建站工具拉开差距的地方。
     * <p>
     * 收录标准：专门用来「假装有图」的服务。{@code source.unsplash.com/random} 也算，
     * 它虽然链到真实照片，但内容是随机的，跟需求无关。
     */
    private static final Pattern PLACEHOLDER_SERVICE = Pattern.compile(
            "via\\.placeholder\\.com|placehold\\.co|picsum\\.photos|dummyimage\\.com|lorempixel\\.com"
                    + "|placekitten\\.com|loremflickr\\.com|fakeimg\\.pl|source\\.unsplash\\.com/random",
            Pattern.CASE_INSENSITIVE);

    /**
     * 任何图片引用（用来判断「是不是整页都用占位图」）
     */
    private static final Pattern ANY_IMAGE = Pattern.compile(
            "<img\\b[^>]*\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']|url\\(\\s*[\"']?([^\"')]+)[\"']?\\s*\\)",
            Pattern.CASE_INSENSITIVE);

    private CommonChecks() {
    }

    /**
     * 目录存在且非空
     */
    private record RootExists() implements ArtifactCheck {

        @Override
        public String name() {
            return "root-exists";
        }

        @Override
        public CheckResult inspect(Path root, ArtifactInspector.Options options) {
            if (root == null || !Files.isDirectory(root)) {
                return CheckResult.fail(name(), "产物目录不存在: " + root);
            }
            List<Path> files = ArtifactFiles.textFiles(root);
            if (files.isEmpty()) {
                return CheckResult.fail(name(), "产物目录里没有任何可检查的文本文件");
            }
            return CheckResult.pass(name(), "共 " + files.size() + " 个文本文件");
        }
    }

    /**
     * 模板残留检查
     */
    private record NoTemplateResidue() implements ArtifactCheck {

        @Override
        public String name() {
            return "no-template-residue";
        }

        @Override
        public CheckResult inspect(Path root, ArtifactInspector.Options options) {
            List<Path> files = ArtifactFiles.textFiles(root);
            if (files.isEmpty()) {
                return CheckResult.skip(name(), "没有可检查的文件");
            }
            List<String> hits = new ArrayList<>();
            for (Path file : files) {
                String content = ArtifactFiles.read(file);
                String rel = ArtifactFiles.relative(root, file);

                String lazyHit = findLazyMark(content);
                if (lazyHit != null) {
                    hits.add(rel + " 含「" + lazyHit + "」");
                }
                // mustache 只对纯 HTML 是异常；.vue 里是合法插值，.js 里极少出现但可能是模板字符串，不查
                if (isHtml(file) && MUSTACHE.matcher(content).find()) {
                    hits.add(rel + " 含未替换的模板占位符「{{」");
                }
            }
            if (hits.isEmpty()) {
                return CheckResult.pass(name(), "未发现占位符残留");
            }
            return CheckResult.fail(name(), summarize(hits));
        }
    }

    /**
     * 渲染坏值检查
     */
    private record NoBrokenValue() implements ArtifactCheck {

        @Override
        public String name() {
            return "no-broken-value";
        }

        @Override
        public CheckResult inspect(Path root, ArtifactInspector.Options options) {
            List<Path> htmlFiles = ArtifactFiles.filesWithExtension(root, "html");
            if (htmlFiles.isEmpty()) {
                return CheckResult.skip(name(), "没有 .html 文件");
            }
            List<String> hits = new ArrayList<>();
            for (Path file : htmlFiles) {
                Matcher matcher = BROKEN_VALUE.matcher(ArtifactFiles.read(file));
                if (matcher.find()) {
                    hits.add(ArtifactFiles.relative(root, file) + " 渲染出「" + matcher.group().trim() + "」");
                }
            }
            if (hits.isEmpty()) {
                return CheckResult.pass(name(), "页面中未出现 undefined/null/NaN");
            }
            return CheckResult.fail(name(), summarize(hits));
        }
    }

    /**
     * 占位图服务检查（软）
     * <p>
     * <b>为什么要数「一共几张图、其中几处是占位图」</b>，而不是只说「引用了 picsum.photos」：
     * 「引用了 picsum」和「整页 8 张图全是 picsum」是两种完全不同的问题。
     * 前者可能只是随手放了个头像占位，后者说明<b>图片收集环节整个没生效</b> ——
     * 而后者才是这个项目要盯的东西。只报域名的话，看报告的人得自己去翻代码才能分辨。
     */
    private record NoPlaceholderService() implements ArtifactCheck {

        @Override
        public String name() {
            return "no-placeholder-service";
        }

        @Override
        public CheckResult inspect(Path root, ArtifactInspector.Options options) {
            List<Path> files = ArtifactFiles.textFiles(root);
            if (files.isEmpty()) {
                return CheckResult.skip(name(), "没有可检查的文件");
            }
            List<String> hits = new ArrayList<>();
            int placeholderCount = 0;
            int imageCount = 0;
            for (Path file : files) {
                String content = ArtifactFiles.read(file);
                String rel = ArtifactFiles.relative(root, file);

                int refs = countMatches(ANY_IMAGE, content);
                imageCount += refs;

                Matcher matcher = PLACEHOLDER_SERVICE.matcher(content);
                int local = 0;
                while (matcher.find()) {
                    local++;
                    placeholderCount++;
                }
                if (local > 0) {
                    hits.add(rel + " 有 " + local + " 处");
                }
            }
            if (hits.isEmpty()) {
                return CheckResult.pass(name(), "未使用占位图服务");
            }
            String detail = summarize(hits);
            if (imageCount > 0 && placeholderCount >= imageCount) {
                // 所有图片都是占位图：不是「顺手放了个占位」，是素材链路整个没跑到
                return CheckResult.warn(name(), "全部 " + imageCount + " 处图片都是占位图，图片收集环节可能没生效（"
                        + detail + "）");
            }
            return CheckResult.warn(name(), "共 " + placeholderCount + " 处占位图（" + detail + "）");
        }

        private int countMatches(Pattern pattern, String content) {
            Matcher matcher = pattern.matcher(content);
            int count = 0;
            while (matcher.find()) {
                count++;
            }
            return count;
        }
    }

    private static boolean isHtml(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".html") || name.endsWith(".htm");
    }

    /**
     * 找出第一处「没做完」的痕迹
     *
     * @return 命中的标记文本；没命中返回 null
     */
    private static String findLazyMark(String content) {
        Matcher mark = LAZY_MARK.matcher(content);
        if (mark.find()) {
            return mark.group();
        }
        Matcher lorem = LOREM.matcher(content);
        if (lorem.find()) {
            return lorem.group();
        }
        return null;
    }

    /**
     * 命中项太多时只留前 3 条 —— 报告的用途是「让人马上知道去改哪」，不是把全部错误抄一遍
     */
    private static String summarize(List<String> hits) {
        if (hits.size() <= 3) {
            return String.join("; ", hits);
        }
        return String.join("; ", hits.subList(0, 3)) + " 等 " + hits.size() + " 处";
    }
}
