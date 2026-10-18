package com.haoy.aiapp.eval.inspect;

import com.haoy.aiapp.model.enums.CodeGenTypeEnum;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 产物检查器 —— 对任意生成目录做一次「体检」
 * <p>
 * <b>它不调用 AI，也不需要 Spring 容器。</b>这是它最大的价值：生成目录里躺着的历史产物，
 * 随时可以扫一遍，看看哪些当时被当成「成功」其实是不合格的。
 * <p>
 * 用法：
 * <pre>
 *   // 日常静态扫描（毫秒级）
 *   ArtifactInspector.inspect(dir, CodeGenTypeEnum.VUE_PROJECT);
 *
 *   // 全量评估（会真跑 npm run build，分钟级）
 *   ArtifactInspector.inspect(dir, CodeGenTypeEnum.VUE_PROJECT, ArtifactInspector.Options.full());
 *
 *   // 扫一整个输出根目录下的所有产物
 *   ArtifactInspector.inspectAll(Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR), Options.staticOnly());
 * </pre>
 *
 * @author <a>程序员haoy</a>
 */
public final class ArtifactInspector {

    private ArtifactInspector() {
    }

    /**
     * 检查选项
     *
     * @param withBuild 是否真的执行 {@code npm run build}（只影响 Vue 工程，且要求 node_modules 已存在）
     */
    public record Options(boolean withBuild) {

        /**
         * 日常扫描：只看静态事实，毫秒级、零依赖
         */
        public static Options staticOnly() {
            return new Options(false);
        }

        /**
         * 全量评估：允许跑构建
         */
        public static Options full() {
            return new Options(true);
        }
    }

    /**
     * 一个产物目录的体检结果
     *
     * @param dir    产物根目录
     * @param type   生成类型
     * @param checks 全部检查项结果（顺序稳定，便于报告 diff）
     */
    public record Inspection(Path dir, CodeGenTypeEnum type, List<CheckResult> checks) {

        /**
         * 是否合格：没有任何硬检查失败
         * <p>
         * 注意：{@code SKIP} 不影响合格 —— 它表示「这条没法判断」，不是「这条坏了」。
         * 但报告里会显示跳过项，避免「10/10 通过」的假象。
         */
        public boolean passed() {
            return checks.stream().noneMatch(CheckResult::isBlocking);
        }

        /**
         * 硬检查失败项
         */
        public List<CheckResult> failures() {
            return checks.stream().filter(CheckResult::isBlocking).toList();
        }

        /**
         * 软检查提示项（不影响合格，但值得看一眼）
         */
        public List<CheckResult> warnings() {
            return checks.stream()
                    .filter(c -> c.status() == CheckResult.Status.FAIL && c.severity() == CheckResult.Severity.SOFT)
                    .toList();
        }

        /**
         * 跳过项
         */
        public List<CheckResult> skipped() {
            return checks.stream().filter(c -> c.status() == CheckResult.Status.SKIP).toList();
        }

        /**
         * 一行摘要，如 {@code [PASS] vue_project_123 (8/10, 2 skip, 1 warn)}
         */
        public String summary() {
            long pass = checks.stream().filter(c -> c.status() == CheckResult.Status.PASS).count();
            return String.format("[%s] %s (%d/%d, %d skip, %d warn)",
                    passed() ? "PASS" : "FAIL",
                    dir == null ? "?" : dir.getFileName(),
                    pass, checks.size(), skipped().size(), warnings().size());
        }

        /**
         * 多行文本报告（CLI / 测试里直接打印）
         */
        public String toText() {
            StringBuilder sb = new StringBuilder(summary()).append('\n');
            for (CheckResult check : checks) {
                String mark = switch (check.status()) {
                    case PASS -> "  ok  ";
                    case FAIL -> check.severity() == CheckResult.Severity.SOFT ? " warn " : " FAIL ";
                    case SKIP -> " skip ";
                };
                sb.append(mark).append(check.name());
                if (check.detail() != null && !check.detail().isBlank()) {
                    sb.append(" — ").append(check.detail());
                }
                sb.append('\n');
            }
            return sb.toString();
        }
    }

    /**
     * 按生成类型取检查项清单
     * <p>
     * <b>顺序是稳定契约</b>：报告按这个顺序输出，历史报告才能逐行 diff。
     * 加新检查时追加到末尾，不要插到中间。
     *
     * @param type 生成类型
     * @return 检查项列表
     */
    public static List<ArtifactCheck> checksFor(CodeGenTypeEnum type) {
        if (type == null) {
            throw new IllegalArgumentException("生成类型不能为空");
        }
        List<ArtifactCheck> checks = new ArrayList<>();
        checks.add(CommonChecks.ROOT_EXISTS);
        checks.add(CommonChecks.NO_TEMPLATE_RESIDUE);
        checks.add(CommonChecks.NO_PLACEHOLDER_SERVICE);
        switch (type) {
            case HTML -> {
                checks.add(CommonChecks.NO_BROKEN_VALUE);
                checks.add(HtmlChecks.SKELETON);
                checks.add(HtmlChecks.CONTENT_SIZE);
                checks.add(HtmlChecks.TAGS_BALANCED);
                checks.add(HtmlChecks.HAS_VISIBLE_CONTENT);
            }
            case MULTI_FILE -> {
                checks.add(CommonChecks.NO_BROKEN_VALUE);
                checks.add(HtmlChecks.SKELETON);
                checks.add(HtmlChecks.CONTENT_SIZE);
                checks.add(HtmlChecks.TAGS_BALANCED);
                checks.add(HtmlChecks.HAS_VISIBLE_CONTENT);
                checks.add(HtmlChecks.ASSETS_EXIST);
            }
            case VUE_PROJECT -> {
                checks.add(VueProjectChecks.PACKAGE_JSON);
                checks.add(VueProjectChecks.ENTRY);
                checks.add(VueProjectChecks.ROOT_COMPONENT);
                checks.add(VueProjectChecks.IMPORTS_RESOLVE);
                checks.add(VueProjectChecks.BUILD_PASSES);
            }
        }
        return List.copyOf(checks);
    }

    /**
     * 静态体检（不跑构建）
     *
     * @param dir  产物根目录
     * @param type 生成类型
     */
    public static Inspection inspect(Path dir, CodeGenTypeEnum type) {
        return inspect(dir, type, Options.staticOnly());
    }

    /**
     * 体检
     *
     * @param dir     产物根目录
     * @param type    生成类型
     * @param options 检查选项
     */
    public static Inspection inspect(Path dir, CodeGenTypeEnum type, Options options) {
        Options effective = options == null ? Options.staticOnly() : options;
        List<CheckResult> results = new ArrayList<>();
        for (ArtifactCheck check : checksFor(type)) {
            CheckResult result;
            try {
                result = check.inspect(dir, effective);
                if (result == null) {
                    result = CheckResult.fail(check.name(), "检查项返回了 null");
                }
            } catch (Exception e) {
                // 检查项自己炸了不能拖垮整轮评估：记成失败，detail 带上异常信息
                result = CheckResult.fail(check.name(), "检查异常: " + e.getClass().getSimpleName() + " " + e.getMessage());
            }
            results.add(result);
        }
        return new Inspection(dir, type, List.copyOf(results));
    }

    /**
     * 扫描一个输出根目录下的所有产物（按目录名推断类型）
     * <p>
     * 目录名规则与落盘代码一致：{@code {codeType}_{appId}}。
     * 认不出类型的目录（比如手工建的）直接跳过，不猜。
     *
     * @param outputRoot 如 {@code {user.dir}/tmp/code_output}
     * @param options    检查选项
     * @return 每个产物目录一份体检结果
     */
    public static List<Inspection> inspectAll(Path outputRoot, Options options) {
        if (outputRoot == null || !Files.isDirectory(outputRoot)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(outputRoot)) {
            return stream
                    .filter(Files::isDirectory)
                    .sorted()
                    .map(dir -> {
                        CodeGenTypeEnum type = inferType(dir);
                        return type == null ? null : inspect(dir, type, options);
                    })
                    .filter(Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 从目录名推断生成类型
     *
     * @param dir 如 {@code vue_project_123}
     * @return 推断不出时返回 null
     */
    public static CodeGenTypeEnum inferType(Path dir) {
        if (dir == null || dir.getFileName() == null) {
            return null;
        }
        String name = dir.getFileName().toString();
        for (CodeGenTypeEnum type : CodeGenTypeEnum.values()) {
            if (name.startsWith(type.getValue() + "_")) {
                return type;
            }
        }
        return null;
    }
}
