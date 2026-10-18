package com.haoy.aiapp.eval;

import com.haoy.aiapp.eval.inspect.ArtifactInspector;
import com.haoy.aiapp.eval.inspect.CheckResult;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * L2 评估执行器 —— 真生成、真检查、真对比
 * <p>
 * <b>它只做三件与 AI 无关的事：调度、判分、出报告。</b>「怎么生成」被 {@link CodeGenerator} 抽走了，
 * 所以整条流水线可以用假生成器跑通 —— 限速、超时、报告、基线 diff 这些逻辑都不需要花钱验证。
 * <p>
 * <b>三条设计约束，都是被现实逼出来的：</b>
 * <ol>
 *   <li><b>单条失败不能中断整轮。</b>一条炸了还有 23 条已经花掉的钱，丢掉重跑是纯浪费。
 *       所以失败记成结果字段，不抛异常。</li>
 *   <li><b>必须逐条限速。</b>24 条连着打必然撞上游限流，而 429 造成的失败会被误读成「质量退化」。</li>
 *   <li><b>必须逐条超时。</b>某条卡住会拖死整轮，而你没法从外面杀掉一个还在跑的 AI 请求 ——
 *       只能放弃等待、继续下一条。</li>
 * </ol>
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
public final class EvalRunner {

    private static final DateTimeFormatter RUN_ID_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private final CodeGenerator generator;

    private final ArtifactInspector.Options inspectOptions;

    private final Throttle throttle;

    /**
     * 评估专用 appId 的起点
     * <p>
     * 每条用例分配一个独立 ID（起点 + 序号），产物落到各自的 {@code {type}_{appId}} 目录。
     * 用独立段而不是复用真实 appId，是为了不覆盖用户真在用的应用产物 ——
     * 评估会把 {@code dist} 都重新构建一遍，覆盖了很麻烦。
     */
    private final long appIdBase;

    public EvalRunner(CodeGenerator generator,
                      ArtifactInspector.Options inspectOptions,
                      Throttle throttle,
                      long appIdBase) {
        this.generator = generator;
        this.inspectOptions = inspectOptions == null ? ArtifactInspector.Options.staticOnly() : inspectOptions;
        this.throttle = throttle == null ? Throttle.unlimited() : throttle;
        this.appIdBase = appIdBase;
    }

    /**
     * 跑一轮评估（纯计算，不写文件）
     *
     * @param cases 用例列表
     * @return 报告
     */
    public EvalReport run(List<EvalCase> cases) {
        String runId = RUN_ID_FORMAT.format(Instant.now());
        List<CaseResult> results = new ArrayList<>();

        // 虚拟线程 + 单条超时：AI 请求是阻塞的，卡住时没法从外部杀掉，只能放弃等待
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            int index = 0;
            for (EvalCase evalCase : cases) {
                long appId = appIdBase + index;
                index++;

                throttle.acquire();
                log.info("[{}/{}] {} → 开始生成（appId={}）", index, cases.size(), evalCase.id(), appId);
                CaseResult result = runOne(evalCase, appId, executor);
                results.add(result);
                log.info("[{}/{}] {} → {}（{} ms）{}", index, cases.size(), evalCase.id(),
                        result.passed() ? "通过" : "失败", result.durationMs(),
                        result.passed() ? "" : " 原因: " + result.failureSummary());
            }
        }

        EvalReport report = new EvalReport(runId, Instant.now(), results);
        log.info("评估完成：{}/{} 通过，路由准确率 {}%", report.passedCount(), report.cases().size(),
                String.format("%.1f", report.routingAccuracy() * 100));
        return report;
    }

    /**
     * 跑一条用例
     */
    private CaseResult runOne(EvalCase evalCase, long appId, ExecutorService executor) {
        long start = System.currentTimeMillis();
        CodeGenerator.Generated generated;

        Future<CodeGenerator.Generated> future = executor.submit(() -> generator.generate(evalCase, appId));
        try {
            generated = future.get(evalCase.timeoutSec(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // 只能放弃等待：工作流跑在自己的虚拟线程上，外部中断通常打不进去
            future.cancel(true);
            generated = CodeGenerator.Generated.failed("生成超时（" + evalCase.timeoutSec() + "s）");
        } catch (ExecutionException e) {
            generated = CodeGenerator.Generated.failed("生成异常: " + rootMessage(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            generated = CodeGenerator.Generated.failed("评估被中断");
        }
        long duration = System.currentTimeMillis() - start;

        if (!generated.succeeded()) {
            return new CaseResult(evalCase.id(), evalCase.expectedType(), generated.actualType(),
                    generated.artifactDir(), List.of(), List.of(), duration, generated.error());
        }

        // 用「实际类型」选检查器：产物是什么就该按什么标准检查，而不是按你希望它是什么
        List<CheckResult> checks = ArtifactInspector
                .inspect(generated.artifactDir(), generated.actualType(), inspectOptions)
                .checks();
        List<String> caseFailures = checkCaseAssertions(evalCase, generated.artifactDir());

        return new CaseResult(evalCase.id(), evalCase.expectedType(), generated.actualType(),
                generated.artifactDir(), checks, caseFailures, duration, null);
    }

    /**
     * 用例级断言：需求里明确要求的东西在不在、明确禁止的在不在
     */
    private List<String> checkCaseAssertions(EvalCase evalCase, Path artifactDir) {
        if ((evalCase.mustInclude().isEmpty() && evalCase.mustNotInclude().isEmpty()) || artifactDir == null) {
            return List.of();
        }
        String text = ArtifactInspector.readAllText(artifactDir);
        List<String> failures = new ArrayList<>();
        for (String required : evalCase.mustInclude()) {
            if (!text.contains(required)) {
                failures.add("缺少必需内容「" + required + "」");
            }
        }
        for (String forbidden : evalCase.mustNotInclude()) {
            if (text.contains(forbidden)) {
                failures.add("出现禁止内容「" + forbidden + "」");
            }
        }
        return failures;
    }

    /**
     * 取最内层异常的消息 —— 外面那几层包装通常只是「生成失败」，没信息量
     */
    private String rootMessage(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName() + ": " + current.getMessage();
    }

    /**
     * 把报告写成 JSON 和 markdown
     *
     * @param report    报告
     * @param outputDir 输出目录（不存在会自动创建）
     * @return 写出的 JSON 路径
     */
    public static Path writeReport(EvalReport report, EvalReport.Baseline baseline, Path outputDir)
            throws IOException {
        Files.createDirectories(outputDir);
        Path json = outputDir.resolve("report-" + report.runId() + ".json");
        Path md = outputDir.resolve("report-" + report.runId() + ".md");
        Files.writeString(json, report.toJson(), StandardCharsets.UTF_8);
        Files.writeString(md, report.toMarkdown(baseline), StandardCharsets.UTF_8);
        return json;
    }

    /**
     * 读基线；文件不存在时返回 null（第一轮本来就没有基线）
     *
     * @param baselineFile 基线 JSON 路径
     */
    public static EvalReport.Baseline readBaseline(Path baselineFile) throws IOException {
        if (baselineFile == null || !Files.isRegularFile(baselineFile)) {
            return null;
        }
        return EvalReport.Baseline.fromJson(Files.readString(baselineFile, StandardCharsets.UTF_8));
    }
}
