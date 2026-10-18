package com.haoy.aiapp.eval.inspect;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Vue 工程模式检查
 * <p>
 * Vue 工程和 HTML 单页的本质区别：<b>它的产物不是「一个能打开的文件」，而是「一个能构建的工程」。</b>
 * 文件都在、但 {@code npm run build} 失败，对用户来说等于什么都没生成。
 * 所以这里最硬的一条是 {@link BuildPasses}：「真的跑一遍构建」。
 * <p>
 * <b>为什么它默认关闭？</b>一次 build 要 30~60 秒（还是热缓存的情况下），
 * 而 {@code vue-build-passes} 只有在「全量评估」时才值得付这个时间。日常静态扫描用
 * {@code vue-imports-resolve} 兜底 —— 它能抓住 build 失败里最常见的那个原因（引用了不存在的文件），
 * 但代价只有几毫秒。
 *
 * @author <a>程序员haoy</a>
 */
public final class VueProjectChecks {

    /**
     * package.json 存在且声明了 vue 依赖
     */
    public static final ArtifactCheck PACKAGE_JSON = new PackageJson();

    /**
     * 入口文件存在
     */
    public static final ArtifactCheck ENTRY = new Entry();

    /**
     * 根组件存在
     */
    public static final ArtifactCheck ROOT_COMPONENT = new RootComponent();

    /**
     * 相对 import 都能解析到真实文件（build 失败的高频原因，零成本版）
     */
    public static final ArtifactCheck IMPORTS_RESOLVE = new ImportsResolve();

    /**
     * 真的跑一遍 {@code npm run build}（最硬的一条，最慢的一条）
     */
    public static final ArtifactCheck BUILD_PASSES = new BuildPasses();

    /**
     * 省略扩展名时的候选后缀（Vite 默认解析顺序）
     */
    private static final List<String> EXTENSIONS = List.of(".js", ".ts", ".mjs", ".jsx", ".tsx", ".vue", ".json");

    /**
     * 目录入口文件
     */
    private static final List<String> INDEX_FILES = List.of("index.js", "index.ts", "index.vue");

    /**
     * {@code from 'x'} / {@code import('x')} / {@code import 'x'}
     */
    private static final Pattern IMPORT_SOURCE = Pattern.compile(
            "(?:from\\s*|import\\s*\\(\\s*|import\\s+)[\"']([^\"']+)[\"']");

    private VueProjectChecks() {
    }

    /**
     * package.json 与 vue 依赖
     */
    private record PackageJson() implements ArtifactCheck {

        @Override
        public String name() {
            return "vue-package-json";
        }

        @Override
        public CheckResult inspect(Path root, ArtifactInspector.Options options) {
            Path pkg = root.resolve("package.json");
            if (!Files.isRegularFile(pkg)) {
                return CheckResult.fail(name(), "缺少 package.json");
            }
            try {
                JSONObject json = JSONUtil.parseObj(ArtifactFiles.read(pkg));
                List<String> missing = new ArrayList<>();
                if (!hasDependency(json, "vue")) {
                    missing.add("vue");
                }
                if (!hasDependency(json, "vite")) {
                    missing.add("vite");
                }
                if (missing.isEmpty()) {
                    return CheckResult.pass(name(), "vue + vite 声明齐全");
                }
                return CheckResult.fail(name(), "dependencies 缺少: " + String.join(", ", missing));
            } catch (Exception e) {
                return CheckResult.fail(name(), "package.json 不是合法 JSON: " + e.getMessage());
            }
        }

        /**
         * 依赖可能正经声明在 dependencies，也可能被 AI 塞进 devDependencies —— 两边都认
         */
        private boolean hasDependency(JSONObject json, String name) {
            return containsKey(json, "dependencies", name) || containsKey(json, "devDependencies", name);
        }

        private boolean containsKey(JSONObject json, String section, String name) {
            JSONObject deps = json.getJSONObject(section);
            return deps != null && deps.containsKey(name);
        }
    }

    /**
     * 入口文件
     */
    private record Entry() implements ArtifactCheck {

        @Override
        public String name() {
            return "vue-entry";
        }

        @Override
        public CheckResult inspect(Path root, ArtifactInspector.Options options) {
            for (String candidate : List.of("src/main.ts", "src/main.js", "src/main.mjs")) {
                if (Files.isRegularFile(root.resolve(candidate))) {
                    return CheckResult.pass(name(), candidate);
                }
            }
            return CheckResult.fail(name(), "src/ 下没有 main.ts / main.js");
        }
    }

    /**
     * 根组件
     */
    private record RootComponent() implements ArtifactCheck {

        @Override
        public String name() {
            return "vue-root-component";
        }

        @Override
        public CheckResult inspect(Path root, ArtifactInspector.Options options) {
            if (Files.isRegularFile(root.resolve("src/App.vue"))) {
                return CheckResult.pass(name(), "src/App.vue");
            }
            return CheckResult.fail(name(), "缺少 src/App.vue");
        }
    }

    /**
     * 相对 import 可解析
     */
    private record ImportsResolve() implements ArtifactCheck {

        @Override
        public String name() {
            return "vue-imports-resolve";
        }

        @Override
        public CheckResult inspect(Path root, ArtifactInspector.Options options) {
            List<Path> sources = ArtifactFiles.textFiles(root).stream()
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        return n.endsWith(".js") || n.endsWith(".ts") || n.endsWith(".vue") || n.endsWith(".mjs");
                    })
                    .toList();
            if (sources.isEmpty()) {
                return CheckResult.skip(name(), "没有可检查的源码文件");
            }

            Set<String> unresolved = new LinkedHashSet<>();
            int checked = 0;
            for (Path source : sources) {
                Matcher matcher = IMPORT_SOURCE.matcher(ArtifactFiles.read(source));
                while (matcher.find()) {
                    String spec = matcher.group(1).trim();
                    Path base = resolveSpecifier(root, source, spec);
                    if (base == null) {
                        continue;
                    }
                    checked++;
                    if (!resolves(base)) {
                        unresolved.add(ArtifactFiles.relative(root, source) + " → " + spec);
                    }
                }
            }
            if (checked == 0) {
                return CheckResult.skip(name(), "没有相对 import");
            }
            if (unresolved.isEmpty()) {
                return CheckResult.pass(name(), checked + " 个相对 import 全部可解析");
            }
            List<String> list = new ArrayList<>(unresolved);
            String detail = list.size() <= 3
                    ? String.join("; ", list)
                    : String.join("; ", list.subList(0, 3)) + " 等 " + list.size() + " 处";
            return CheckResult.fail(name(), "解析不到: " + detail);
        }

        /**
         * 把 import 说明符换算成文件系统上的基路径
         * <p>
         * <b>为什么要特殊处理 {@code @/}</b>：这是 Vite + Vue 模板里最常见的别名（映射到 {@code src}）。
         * 如果工程 {@code vite.config.js} 里没配 alias 却用了 {@code @/}，构建必然失败 —— 那正是要抓的坑。
         * 按 {@code src/} 解析正好能把这种情况标出来。
         *
         * @return null 表示「不需要检查」（第三方包、虚拟模块、绝对 URL）
         */
        private Path resolveSpecifier(Path root, Path source, String spec) {
            if (spec.startsWith("@/")) {
                return root.resolve("src").resolve(spec.substring(2));
            }
            if (spec.startsWith(".")) {
                return source.getParent().resolve(spec).normalize();
            }
            return null;
        }

        private boolean resolves(Path base) {
            if (Files.isRegularFile(base)) {
                return true;
            }
            for (String ext : EXTENSIONS) {
                if (Files.isRegularFile(base.resolveSibling(base.getFileName() + ext))) {
                    return true;
                }
            }
            for (String index : INDEX_FILES) {
                if (Files.isRegularFile(base.resolve(index))) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * 真跑一遍构建
     */
    private record BuildPasses() implements ArtifactCheck {

        /**
         * 单个工程的构建超时（秒）
         * <p>
         * 60 秒是正常 Vue 工程冷构建的上限。给到 180 是为了容忍低配机器和首次依赖预构建，
         * 但不能再高了 —— 一条超时的用例会把整轮评估拖住三分钟。
         */
        private static final int TIMEOUT_SECONDS = 180;

        /**
         * 失败时保留的日志尾巴长度
         */
        private static final int TAIL_CHARS = 600;

        @Override
        public String name() {
            return "vue-build-passes";
        }

        @Override
        public CheckResult inspect(Path root, ArtifactInspector.Options options) {
            if (!options.withBuild()) {
                return CheckResult.skip(name(), "未开启构建检查（Options.withBuild=false）");
            }
            if (!Files.isRegularFile(root.resolve("package.json"))) {
                return CheckResult.skip(name(), "缺少 package.json");
            }
            if (!Files.isDirectory(root.resolve("node_modules"))) {
                // 不替用户跑 npm install：那会改动产物目录，而且评估器不该联网装包
                return CheckResult.skip(name(), "缺少 node_modules，跳过构建（请先手动 npm install）");
            }

            Path log = null;
            try {
                log = Files.createTempFile("vite-build-", ".log");
                ProcessBuilder builder = new ProcessBuilder(npmCommand(), "run", "build")
                        .directory(root.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile());
                // Windows 下 npm 是 .cmd，必须走 cmd 解释器，否则 ProcessBuilder 直接找不到可执行文件
                if (isWindows()) {
                    builder = new ProcessBuilder("cmd", "/c", "npm", "run", "build")
                            .directory(root.toFile())
                            .redirectErrorStream(true)
                            .redirectOutput(log.toFile());
                }
                Process process = builder.start();
                boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    return CheckResult.fail(name(), "构建超时（" + TIMEOUT_SECONDS + "s）");
                }
                int exit = process.exitValue();
                if (exit == 0) {
                    return CheckResult.pass(name(), "构建成功");
                }
                return CheckResult.fail(name(), "构建失败（exit " + exit + "）: " + tail(log));
            } catch (IOException e) {
                return CheckResult.fail(name(), "无法启动构建进程: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return CheckResult.fail(name(), "构建被中断");
            } finally {
                deleteQuietly(log);
            }
        }

        private String npmCommand() {
            return isWindows() ? "npm.cmd" : "npm";
        }

        private boolean isWindows() {
            return System.getProperty("os.name", "").toLowerCase().contains("win");
        }

        /**
         * 取构建日志的尾巴
         * <p>
         * 构建错误（尤其 Rollup 的）总是打在最后几十行，取开头看到的全是「vite v5.x building…」。
         */
        private String tail(Path log) {
            try {
                String content = Files.readString(log, StandardCharsets.UTF_8);
                String compact = content.replaceAll("\\s+", " ").trim();
                if (compact.length() <= TAIL_CHARS) {
                    return compact;
                }
                return "…" + compact.substring(compact.length() - TAIL_CHARS);
            } catch (IOException e) {
                return "(日志读取失败)";
            }
        }

        private void deleteQuietly(Path log) {
            if (log == null) {
                return;
            }
            try {
                Files.deleteIfExists(log);
            } catch (IOException ignored) {
                // 临时文件删不掉不影响结果
            }
        }
    }
}
