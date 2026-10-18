package com.haoy.aiapp.eval;

import com.haoy.aiapp.eval.inspect.ArtifactInspector;
import com.haoy.aiapp.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L2 评估器的流程测试
 * <p>
 * <b>全部用假生成器，一次 AI 都不调。</b>这是刻意的：调度、超时、判分、报告、基线 diff
 * 这些逻辑跟「AI 今天写得好不好」没有关系，用真生成去测它们，既慢又贵，而且失败时
 * 你分不清是逻辑错了还是模型抽风。
 * <p>
 * 真生成只在「跑基线」时用一次，那是评估的<b>使用</b>，不是评估<b>代码</b>的测试。
 *
 * @author <a>程序员haoy</a>
 */
class EvalRunnerTest {

    private static Path fixture(String name) {
        var url = Objects.requireNonNull(
                EvalRunnerTest.class.getResource("/eval/fixtures/" + name), "找不到 fixture: " + name);
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static EvalCase aCase(String id, CodeGenTypeEnum type) {
        return new EvalCase(id, "需求文本", type, List.of(), List.of(), 30, "测试用例");
    }

    private static EvalRunner runner(CodeGenerator generator) {
        return new EvalRunner(generator, ArtifactInspector.Options.staticOnly(), Throttle.unlimited(), 900000L);
    }

    @Test
    @DisplayName("全部合格：通过率和路由准确率都是 100%")
    void allPassing() {
        CodeGenerator generator = (evalCase, appId) -> switch (evalCase.id()) {
            case "html" -> CodeGenerator.Generated.ok(CodeGenTypeEnum.HTML, fixture("html-good"));
            default -> CodeGenerator.Generated.ok(CodeGenTypeEnum.MULTI_FILE, fixture("multi-good"));
        };

        EvalReport report = runner(generator).run(List.of(
                aCase("html", CodeGenTypeEnum.HTML),
                aCase("multi", CodeGenTypeEnum.MULTI_FILE)));

        assertThat(report.passRate()).isEqualTo(1.0);
        assertThat(report.routingAccuracy()).isEqualTo(1.0);
        assertThat(report.cases()).allMatch(CaseResult::passed);
    }

    @Test
    @DisplayName("产物有问题：该条失败，其他条不受影响")
    void oneBrokenArtifactDoesNotAffectOthers() {
        CodeGenerator generator = (evalCase, appId) -> "broken".equals(evalCase.id())
                ? CodeGenerator.Generated.ok(CodeGenTypeEnum.HTML, fixture("html-broken"))
                : CodeGenerator.Generated.ok(CodeGenTypeEnum.HTML, fixture("html-good"));

        EvalReport report = runner(generator).run(List.of(
                aCase("good", CodeGenTypeEnum.HTML),
                aCase("broken", CodeGenTypeEnum.HTML)));

        assertThat(report.passRate()).isEqualTo(0.5);
        assertThat(report.cases())
                .filteredOn(c -> c.caseId().equals("broken"))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.passed()).isFalse();
                    assertThat(c.failureSummary()).anyMatch(f -> f.startsWith("html-skeleton"));
                });
    }

    @Test
    @DisplayName("生成抛异常：记成失败而不是中断整轮")
    void generationFailureIsRecordedNotThrown() {
        CodeGenerator generator = (evalCase, appId) -> {
            if ("boom".equals(evalCase.id())) {
                throw new IllegalStateException("模型返回了空响应");
            }
            return CodeGenerator.Generated.ok(CodeGenTypeEnum.HTML, fixture("html-good"));
        };

        EvalReport report = runner(generator).run(List.of(
                aCase("boom", CodeGenTypeEnum.HTML),
                aCase("fine", CodeGenTypeEnum.HTML)));

        assertThat(report.cases()).hasSize(2);
        assertThat(report.passedCount()).isEqualTo(1);
        assertThat(report.cases())
                .filteredOn(c -> c.caseId().equals("boom"))
                .singleElement()
                .satisfies(c -> assertThat(c.error()).contains("模型返回了空响应"));
    }

    @Test
    @DisplayName("生成卡住：超时后放弃等待，继续跑下一条")
    void timeoutDoesNotBlockTheWholeRun() {
        CodeGenerator generator = (evalCase, appId) -> {
            if ("slow".equals(evalCase.id())) {
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return CodeGenerator.Generated.ok(CodeGenTypeEnum.HTML, fixture("html-good"));
        };

        EvalCase slow = new EvalCase("slow", "需求", CodeGenTypeEnum.HTML, List.of(), List.of(), 1, "超时用例");
        long start = System.currentTimeMillis();
        EvalReport report = runner(generator).run(List.of(slow, aCase("fast", CodeGenTypeEnum.HTML)));
        long elapsed = System.currentTimeMillis() - start;

        assertThat(report.cases())
                .filteredOn(c -> c.caseId().equals("slow"))
                .singleElement()
                .satisfies(c -> assertThat(c.error()).contains("超时"));
        // 关键：没有被 5 秒拖住，说明超时真的生效了
        assertThat(elapsed).isLessThan(4_000);
        assertThat(report.passedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("路由判错：产物合格也算通过，但路由准确率下降")
    void routingMismatchIsTrackedSeparately() {
        CodeGenerator generator = (evalCase, appId) ->
                CodeGenerator.Generated.ok(CodeGenTypeEnum.HTML, fixture("html-good"));

        EvalReport report = runner(generator).run(List.of(
                aCase("expect-vue", CodeGenTypeEnum.VUE_PROJECT),
                aCase("expect-html", CodeGenTypeEnum.HTML)));

        assertThat(report.passRate()).isEqualTo(1.0);
        assertThat(report.routingAccuracy()).isEqualTo(0.5);
        assertThat(report.cases())
                .filteredOn(c -> c.caseId().equals("expect-vue"))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.routingCorrect()).isFalse();
                    assertThat(c.passed()).as("路由错了但产物能用，仍算产物合格").isTrue();
                });
    }

    @Test
    @DisplayName("用例级断言：缺少必需内容要算失败")
    void mustIncludeAssertionFails() {
        EvalCase withRequirement = new EvalCase("must", "需求", CodeGenTypeEnum.HTML,
                List.of("<nav", "<footer"), List.of(), 30, "要求有导航和页脚");
        CodeGenerator generator = (evalCase, appId) ->
                CodeGenerator.Generated.ok(CodeGenTypeEnum.HTML, fixture("html-todo-app"));

        EvalReport report = runner(generator).run(List.of(withRequirement));

        assertThat(report.cases())
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.passed()).isFalse();
                    // todo 应用没有 nav 也没有 footer，应该两条都报出来
                    assertThat(c.failureSummary()).hasSize(2);
                });
    }

    @Test
    @DisplayName("报告能落盘并被读回成基线，diff 能认出退化和修复")
    void reportRoundTripAndDiff(@TempDir Path temp) throws IOException {
        // 第一轮：A 过、B 不过
        EvalReport first = new EvalReport("run-1", null, List.of(
                caseResult("A", true), caseResult("B", false)));
        Path json = EvalRunner.writeReport(first, null, temp);
        assertThat(Files.isRegularFile(json)).isTrue();
        assertThat(Files.isRegularFile(temp.resolve("report-run-1.md"))).isTrue();

        EvalReport.Baseline baseline = EvalRunner.readBaseline(json);
        assertThat(baseline.passedByCase()).containsEntry("A", true).containsEntry("B", false);

        // 第二轮：A 退化、B 修好
        EvalReport second = new EvalReport("run-2", null, List.of(
                caseResult("A", false), caseResult("B", true)));
        EvalReport.Diff diff = second.compareTo(baseline);

        assertThat(diff.regressed()).containsExactly("A");
        assertThat(diff.fixed()).containsExactly("B");
        assertThat(diff.hasRegression()).isTrue();
        assertThat(diff.passRateBefore()).isEqualTo(0.5);
        assertThat(diff.passRateAfter()).isEqualTo(0.5);

        assertThat(second.toMarkdown(baseline))
                .contains("退化 1 条")
                .contains("修复 1 条");
    }

    @Test
    @DisplayName("基线里有用例本次没跑：标成缺失，而不是静默忽略")
    void missingCasesAreReported() {
        EvalReport baselineReport = new EvalReport("run-1", null, List.of(
                caseResult("A", true), caseResult("GONE", true)));
        EvalReport.Baseline baseline = EvalReport.Baseline.fromJson(baselineReport.toJson());

        EvalReport.Diff diff = new EvalReport("run-2", null, List.of(caseResult("A", true))).compareTo(baseline);

        assertThat(diff.missing()).containsExactly("GONE");
        assertThat(diff.regressed()).isEmpty();
    }

    @Test
    @DisplayName("没有任何用例时，指标是 0 而不是 NaN")
    void emptyReportHasZeroRates() {
        EvalReport report = new EvalReport("run-1", null, List.of());

        assertThat(report.passRate()).isZero();
        assertThat(report.routingAccuracy()).isZero();
        assertThat(report.avgDurationMs()).isZero();
    }

    /**
     * 构造一条只有通过状态、没有明细的 CaseResult
     */
    private static CaseResult caseResult(String id, boolean passed) {
        return new CaseResult(id, CodeGenTypeEnum.HTML, CodeGenTypeEnum.HTML, null,
                List.of(), passed ? List.of() : List.of("人为构造的失败"), 100, null);
    }
}
