package com.haoy.aiapp.eval;

import com.haoy.aiapp.eval.inspect.ArtifactInspector;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import jakarta.annotation.Resource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * L2 端到端评估的真实入口 —— <b>会花钱，默认不跑</b>
 * <p>
 * 用法：
 * <pre>
 *   # 全量跑一轮（需要配好 API Key，会调几十次大模型）
 *   mvn test -Dtest=EvalRegressionTest -Dtest.groups=requires-ai
 *
 *   # 指定用例目录
 *   mvn test -Dtest=EvalRegressionTest -Dtest.groups=requires-ai -Deval.cases.dir=../.workbuddy/eval/cases
 * </pre>
 * <p>
 * <b>它不在常规测试里跑，理由有三条：</b>
 * <ol>
 *   <li>一轮几十次生成、几块钱、半小时 —— CI 每次跑不起，也不该跑；</li>
 *   <li>结果受上游限流和模型波动影响，红了不代表代码坏了；</li>
 *   <li>它的产物是<b>一份报告</b>，不是「绿/红」——所以要人去读，而不是靠 CI 判。</li>
 * </ol>
 * <p>
 * 第一次跑会自动把结果存成 {@code target/eval/baseline.json}；之后每次都会拿它做 diff，
 * 报告开头那段「通过率 52% → 58%，退化 2 条」就是你要的答案。
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
@Tag("requires-ai")
@SpringBootTest
class EvalRegressionTest {

    /**
     * 评估专用 appId 起点，避开真实应用的 ID 段
     */
    private static final long EVAL_APP_ID_BASE = 900_000L;

    /**
     * 限速：每分钟 8 次
     * <p>
     * 这个值不是拍的 —— 它要比上游的 RPM 限制低一档。定高了会在第 10 条左右开始 429，
     * 而那些失败会被当成「生成质量下降」，白读一轮报告。
     */
    private static final int MAX_PER_MINUTE = 8;

    @Resource
    private WorkflowCodeGenerator workflowCodeGenerator;

    @Test
    void runEvaluation() throws IOException {
        Path caseDir = resolveCaseDir();
        assumeTrue(caseDir != null && Files.isDirectory(caseDir), "没有用例目录，跳过：" + caseDir);

        List<EvalCase> cases = EvalCase.loadAll(caseDir);
        assumeTrue(!cases.isEmpty(), "用例目录里没有 .json 用例：" + caseDir);

        log.info("开始 L2 评估：{} 条用例，限速 {}/分钟，用例目录 {}", cases.size(), MAX_PER_MINUTE, caseDir);

        EvalRunner runner = new EvalRunner(
                workflowCodeGenerator,
                // 全量评估开构建检查：能不能 npm build 是 Vue 类型最硬的判据
                ArtifactInspector.Options.full(),
                Throttle.of(MAX_PER_MINUTE, 60),
                EVAL_APP_ID_BASE);

        EvalReport report = runner.run(cases);

        Path outputDir = Path.of("target", "eval");
        Files.createDirectories(outputDir);
        Path baselineFile = outputDir.resolve("baseline.json");
        EvalReport.Baseline baseline = EvalRunner.readBaseline(baselineFile);

        Path json = EvalRunner.writeReport(report, baseline, outputDir);
        log.info("报告已写入: {}", json.toAbsolutePath());

        if (baseline == null) {
            // 第一轮：把本次结果存成基线，下一轮才有对比对象
            Files.copy(json, baselineFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.info("首次运行，已建立基线: {}", baselineFile.toAbsolutePath());
        } else {
            EvalReport.Diff diff = report.compareTo(baseline);
            if (diff.hasRegression()) {
                log.warn("有 {} 条用例相比基线退化: {}", diff.regressed().size(), diff.regressed());
            } else {
                log.info("没有用例退化");
            }
        }
    }

    /**
     * 定用例目录：显式指定 &gt; 私有用例集 &gt; 仓库内格式示例
     * <p>
     * 真实用例集放在 {@code .workbuddy/eval/cases}（不进仓库，属于个人评测语料）；
     * 别人 clone 下来没有这个目录，就回落到 {@code src/test/resources/eval/cases} 里那条示例 ——
     * 至少能跑通一条，看得到报告长什么样，而不是一上来就被 skip 掉。
     *
     * @return 用例目录，都不存在时返回 null
     */
    private Path resolveCaseDir() {
        String configured = System.getProperty("eval.cases.dir");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        Path privateCases = Path.of("../.workbuddy/eval/cases").toAbsolutePath().normalize();
        if (Files.isDirectory(privateCases)) {
            return privateCases;
        }
        return Path.of("src/test/resources/eval/cases").toAbsolutePath().normalize();
    }
}
