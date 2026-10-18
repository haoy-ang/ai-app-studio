package com.haoy.aiapp.eval.inspect;

import com.haoy.aiapp.constant.AppConstant;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 对 {@code tmp/code_output} 下的真实历史产物做一次体检
 * <p>
 * <b>它和 {@link ArtifactInspectorTest} 的区别：</b>
 * 那个测的是「检查器判得对不对」（fixture 是手写的，答案已知）；
 * 这个测的是「历史上真实生成的东西到底什么水平」—— 不预设答案，只出报告。
 * <p>
 * <b>为什么不做断言？</b>因为历史产物里本来就可能有坏东西，断言「必须全过」等于让测试替产品的历史背锅。
 * 这里的价值是报告，不是红绿：跑一次就能拿到「哪些当时被当成成功、其实不合格」的清单。
 * <p>
 * 目录不存在时自动跳过 —— CI 上没有 {@code tmp/code_output}，不会因此变红。
 *
 * @author <a>程序员haoy</a>
 */
class ArtifactScanReportTest {

    @Test
    void scanHistoricalArtifacts() throws IOException {
        Path root = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR);
        assumeTrue(Files.isDirectory(root), "没有历史产物目录，跳过");

        // 默认不跑构建（毫秒级）；需要验证「真的能 build」时加 -Dscan.withBuild=true
        ArtifactInspector.Options options = Boolean.getBoolean("scan.withBuild")
                ? ArtifactInspector.Options.full()
                : ArtifactInspector.Options.staticOnly();

        List<ArtifactInspector.Inspection> inspections = ArtifactInspector.inspectAll(root, options);
        assumeTrue(!inspections.isEmpty(), "目录里没有可识别的产物目录");

        StringBuilder report = new StringBuilder()
                .append("# 历史产物体检报告\n\n")
                .append("扫描目录：`").append(root).append("`\n\n")
                .append("生成时间：").append(LocalDateTime.now()
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n\n");

        long failed = inspections.stream().filter(i -> !i.passed()).count();
        report.append("共 ").append(inspections.size()).append(" 个产物，")
                .append(failed).append(" 个不合格。\n\n");
        report.append("| 产物 | 结论 | 失败项 |\n|---|---|---|\n");
        for (ArtifactInspector.Inspection inspection : inspections) {
            String failures = inspection.failures().isEmpty()
                    ? "-"
                    : String.join(", ", inspection.failures().stream().map(CheckResult::name).toList());
            report.append("| ").append(inspection.dir().getFileName())
                    .append(" | ").append(inspection.passed() ? "通过" : "**不合格**")
                    .append(" | ").append(failures).append(" |\n");
        }
        report.append('\n');
        for (ArtifactInspector.Inspection inspection : inspections) {
            report.append("## ").append(inspection.dir().getFileName()).append("\n\n")
                    .append("```\n").append(inspection.toText()).append("```\n\n");
        }

        Path outDir = Path.of("target", "eval");
        Files.createDirectories(outDir);
        Path outFile = outDir.resolve("scan-report.md");
        Files.writeString(outFile, report.toString(), StandardCharsets.UTF_8);

        System.out.println(report);
        System.out.println("报告已写入: " + outFile.toAbsolutePath());

        assertThat(inspections).isNotEmpty();
    }
}
