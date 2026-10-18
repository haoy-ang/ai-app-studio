package com.haoy.aiapp.eval;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.haoy.aiapp.model.enums.CodeGenTypeEnum;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一轮评估的报告
 * <p>
 * <b>这套东西的灵魂不是通过率，是 {@link Baseline} 和 {@link Diff}。</b>
 * 孤立的一个「通过率 52%」说明不了任何事 —— 你不知道它昨天是 50 还是 70。
 * 只有和上一次的结果比，才能回答「这次改 prompt 是变好还是变坏」。
 * <p>
 * 所以报告要落盘成 JSON：机器读它做 diff，人读 markdown。
 *
 * @param runId     本次运行的标识（时间戳），用于区分文件
 * @param startedAt 开始时间
 * @param cases     逐条结果
 * @author <a>程序员haoy</a>
 */
public record EvalReport(String runId, Instant startedAt, List<CaseResult> cases) {

    private static final DateTimeFormatter HUMAN_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public EvalReport {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    /**
     * 通过率：合格用例 / 全部用例
     * <p>
     * 没有用例时返回 0 而不是 NaN —— 报告里出现 NaN 会污染 diff（{@code NaN != NaN} 恒成立）。
     */
    public double passRate() {
        if (cases.isEmpty()) {
            return 0;
        }
        return (double) cases.stream().filter(CaseResult::passed).count() / cases.size();
    }

    /**
     * 路由准确率：AI 判的类型和用例期望一致的比例
     */
    public double routingAccuracy() {
        if (cases.isEmpty()) {
            return 0;
        }
        return (double) cases.stream().filter(CaseResult::routingCorrect).count() / cases.size();
    }

    /**
     * 路由降级的条数（被上游限流/超时打断，模型没机会判断）
     * <p>
     * 它必须和 {@link #routingAccuracy()} 一起看：降级率高的时候，路由准确率是个假数字 ——
     * 分子分母里混了一堆「根本没答」的样本。
     */
    public long routingDegradedCount() {
        return cases.stream().filter(CaseResult::routingDegraded).count();
    }

    /**
     * 真正判错的条数（排除降级）
     */
    public long routingMisjudgedCount() {
        return cases.stream().filter(CaseResult::routingMisjudged).count();
    }

    public long totalDurationMs() {
        return cases.stream().mapToLong(CaseResult::durationMs).sum();
    }

    public long avgDurationMs() {
        return cases.isEmpty() ? 0 : totalDurationMs() / cases.size();
    }

    public long passedCount() {
        return cases.stream().filter(CaseResult::passed).count();
    }

    /**
     * 序列化为 JSON（baseline 也用这个格式存）
     */
    public String toJson() {
        JSONObject root = new JSONObject();
        root.set("runId", runId);
        root.set("startedAt", startedAt == null ? null : startedAt.toString());
        root.set("passRate", passRate());
        root.set("routingAccuracy", routingAccuracy());
        root.set("routingDegradedCount", routingDegradedCount());
        root.set("routingMisjudgedCount", routingMisjudgedCount());
        root.set("totalDurationMs", totalDurationMs());

        JSONArray array = new JSONArray();
        for (CaseResult c : cases) {
            JSONObject item = new JSONObject();
            item.set("caseId", c.caseId());
            item.set("expectedType", c.expectedType() == null ? null : c.expectedType().getValue());
            item.set("actualType", c.actualType() == null ? null : c.actualType().getValue());
            item.set("passed", c.passed());
            item.set("routingCorrect", c.routingCorrect());
            item.set("routingDegraded", c.routingDegraded());
            item.set("routingDegradeReason", c.routingDegradeReason());
            item.set("durationMs", c.durationMs());
            item.set("error", c.error());
            item.set("failures", c.failureSummary());
            array.add(item);
        }
        root.set("cases", array);
        return root.toStringPretty();
    }

    /**
     * 人读的 markdown 报告
     *
     * @param baseline 对比基线；传 null 表示只看本次
     */
    public String toMarkdown(Baseline baseline) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 评估报告\n\n");
        sb.append("- 运行：`").append(runId).append("`\n");
        sb.append("- 时间：").append(startedAt == null ? "-" : HUMAN_TIME.format(startedAt)).append('\n');
        sb.append("- 用例：").append(cases.size()).append(" 条\n");
        sb.append(String.format("- 通过率：%d/%d（%.1f%%）%n",
                passedCount(), cases.size(), passRate() * 100));
        sb.append(String.format("- 路由准确率：%.1f%%%n", routingAccuracy() * 100));
        long degraded = routingDegradedCount();
        if (degraded > 0) {
            sb.append("- ⚠ 路由降级：").append(degraded).append(" 条（上游限流/超时，模型没机会判断）")
                    .append("，真正判错 ").append(routingMisjudgedCount()).append(" 条\n");
        }
        sb.append("- 总耗时：").append(humanDuration(totalDurationMs()))
                .append("（平均 ").append(avgDurationMs()).append(" ms/条）\n\n");

        if (baseline != null) {
            sb.append(renderDiff(baseline));
        }

        sb.append("| 用例 | 路由 | 结果 | 耗时 | 失败项 |\n");
        sb.append("|---|---|---|---|---|\n");
        for (CaseResult c : cases) {
            String routing = c.routingCorrect() ? "✓" : "✗（期望 " + typeOf(c.expectedType())
                    + "，实际 " + typeOf(c.actualType()) + "）";
            if (c.routingDegraded()) {
                routing = "⚠ 降级（" + typeOf(c.actualType()) + "）";
            }
            String failures = c.failureSummary().isEmpty() ? "-" : String.join("; ", c.failureSummary());
            sb.append("| ").append(c.caseId())
                    .append(" | ").append(routing)
                    .append(" | ").append(c.passed() ? "通过" : "**失败**")
                    .append(" | ").append(c.durationMs()).append("ms")
                    .append(" | ").append(failures)
                    .append(" |\n");
        }

        // 降级原因单独列：它属于「环境问题」，不该混在产物质量里
        cases.stream().filter(CaseResult::routingDegraded).forEach(c ->
                sb.append("\n- `").append(c.caseId()).append("` 降级原因：")
                        .append(c.routingDegradeReason() == null ? "(未记录)" : c.routingDegradeReason())
                        .append('\n'));
        return sb.toString();
    }

    /**
     * 渲染与基线的差异 —— 这段才是「变好还是变坏」的答案
     */
    private String renderDiff(Baseline baseline) {
        Diff diff = compareTo(baseline);
        StringBuilder sb = new StringBuilder();
        sb.append("## 与基线对比（").append(baseline.runId()).append("）\n\n");
        sb.append(String.format("- 通过率：%.1f%% → %.1f%% (%+.1f pt)%n",
                diff.passRateBefore() * 100, diff.passRateAfter() * 100,
                (diff.passRateAfter() - diff.passRateBefore()) * 100));
        sb.append(String.format("- 路由准确率：%.1f%% → %.1f%% (%+.1f pt)%n",
                diff.routingBefore() * 100, diff.routingAfter() * 100,
                (diff.routingAfter() - diff.routingBefore()) * 100));
        if (diff.regressed().isEmpty() && diff.fixed().isEmpty()) {
            sb.append("- 没有用例状态变化\n");
        }
        if (!diff.regressed().isEmpty()) {
            sb.append("- **退化 ").append(diff.regressed().size()).append(" 条**：")
                    .append(String.join("、", diff.regressed())).append('\n');
        }
        if (!diff.fixed().isEmpty()) {
            sb.append("- 修复 ").append(diff.fixed().size()).append(" 条：")
                    .append(String.join("、", diff.fixed())).append('\n');
        }
        if (!diff.missing().isEmpty()) {
            sb.append("- 本次缺失（基线有、本次没跑）：")
                    .append(String.join("、", diff.missing())).append('\n');
        }
        if (!diff.added().isEmpty()) {
            sb.append("- 本次新增（基线没有）：")
                    .append(String.join("、", diff.added())).append('\n');
        }
        sb.append('\n');
        return sb.toString();
    }

    /**
     * 与基线比较
     *
     * @param baseline 基线
     * @return 差异
     */
    public Diff compareTo(Baseline baseline) {
        List<String> regressed = new ArrayList<>();
        List<String> fixed = new ArrayList<>();
        for (CaseResult current : cases) {
            Boolean before = baseline.passedByCase().get(current.caseId());
            if (before == null) {
                continue;
            }
            if (before && !current.passed()) {
                regressed.add(current.caseId());
            } else if (!before && current.passed()) {
                fixed.add(current.caseId());
            }
        }
        Set<String> currentIds = new LinkedHashSet<>();
        cases.forEach(c -> currentIds.add(c.caseId()));
        List<String> missing = baseline.passedByCase().keySet().stream()
                .filter(id -> !currentIds.contains(id))
                .toList();
        List<String> added = currentIds.stream()
                .filter(id -> !baseline.passedByCase().containsKey(id))
                .toList();
        return new Diff(regressed, fixed, baseline.passRate(), passRate(),
                baseline.routingAccuracy(), routingAccuracy(), missing, added);
    }

    private static String typeOf(CodeGenTypeEnum type) {
        return type == null ? "未知" : type.getValue();
    }

    private static String humanDuration(long ms) {
        if (ms < 1000) {
            return ms + "ms";
        }
        long seconds = ms / 1000;
        if (seconds < 60) {
            return seconds + "s";
        }
        return (seconds / 60) + "m" + (seconds % 60) + "s";
    }

    /**
     * 基线快照 —— 只记「每条用例过没过」和汇总指标
     * <p>
     * 刻意不存检查项明细：基线的作用是「和下一轮比较」，存全量明细只会让 diff 噪声变大
     * （某个检查项的 detail 文案改一个字，整份 baseline 就变了）。
     *
     * @param runId           基线那次运行的标识
     * @param passRate        基线通过率
     * @param routingAccuracy 基线路由准确率
     * @param passedByCase    用例 → 是否通过
     */
    public record Baseline(String runId, double passRate, double routingAccuracy,
                           Map<String, Boolean> passedByCase) {

        /**
         * 从报告 JSON 读回基线
         *
         * @param json {@link EvalReport#toJson()} 的产物
         * @return 基线
         */
        public static Baseline fromJson(String json) {
            JSONObject root = JSONUtil.parseObj(json);
            Map<String, Boolean> passedByCase = new LinkedHashMap<>();
            JSONArray array = root.getJSONArray("cases");
            if (array != null) {
                for (Object item : array) {
                    JSONObject obj = (JSONObject) item;
                    passedByCase.put(obj.getStr("caseId"), obj.getBool("passed", false));
                }
            }
            return new Baseline(
                    root.getStr("runId"),
                    root.getDouble("passRate", 0.0),
                    root.getDouble("routingAccuracy", 0.0),
                    Map.copyOf(passedByCase));
        }
    }

    /**
     * 两轮结果之间的差异
     *
     * @param regressed    上次过、这次不过 —— 这是唯一真正需要警惕的
     * @param fixed        上次不过、这次过
     * @param passRateBefore 基线通过率
     * @param passRateAfter  本次通过率
     * @param routingBefore  基线路由准确率
     * @param routingAfter   本次路由准确率
     * @param missing      基线里有、本次没跑的用例
     * @param added        本次新增的用例
     */
    public record Diff(List<String> regressed, List<String> fixed,
                       double passRateBefore, double passRateAfter,
                       double routingBefore, double routingAfter,
                       List<String> missing, List<String> added) {

        /**
         * 是否有退化的用例 —— CI 里据此决定要不要报警
         */
        public boolean hasRegression() {
            return !regressed.isEmpty();
        }
    }
}
