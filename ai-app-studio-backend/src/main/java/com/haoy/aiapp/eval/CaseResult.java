package com.haoy.aiapp.eval;

import com.haoy.aiapp.eval.inspect.CheckResult;
import com.haoy.aiapp.model.enums.CodeGenTypeEnum;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 一条用例的评测结果
 * <p>
 * 把「一次生成」拆成三件独立的事，任何一件失败都单独可见：
 * <ol>
 *   <li><b>路由对不对</b> —— {@link #routingCorrect()}，AI 判的类型和用例期望的是否一致；</li>
 *   <li><b>用例级断言过没过</b> —— {@link #caseFailures()}，需求里明确要求/禁止的片段；</li>
 *   <li><b>产物通不通用</b> —— {@link #checks()}，检查器那套骨架/配平/资源/build。</li>
 * </ol>
 * <p>
 * <b>为什么三件事要分开记，而不是只留一个 boolean？</b>
 * 因为它们的修法完全相反：路由错了要改路由提示词；断言没过要改生成提示词；
 * 产物不通可能是落盘代码有问题。混成一个 false，你只知道「坏了」，不知道该往哪看。
 *
 * @param caseId       用例标识
 * @param expectedType 期望类型
 * @param actualType   AI 实际判定的类型，失败时可能为 null
 * @param artifactDir  产物目录
 * @param checks       检查器逐项结果（生成失败时为空）
 * @param caseFailures 用例级断言的失败描述
 * @param durationMs   本次生成耗时
 * @param error        生成阶段的异常信息
 * @param routingDegraded     路由是否降级（主模型被限流/超时，由备用模型或默认值接手）
 * @param routingDegradeReason 降级原因
 * @author <a>程序员haoy</a>
 */
public record CaseResult(
        String caseId,
        CodeGenTypeEnum expectedType,
        CodeGenTypeEnum actualType,
        Path artifactDir,
        List<CheckResult> checks,
        List<String> caseFailures,
        long durationMs,
        String error,
        boolean routingDegraded,
        String routingDegradeReason
) {

    public CaseResult {
        checks = checks == null ? List.of() : List.copyOf(checks);
        caseFailures = caseFailures == null ? List.of() : List.copyOf(caseFailures);
    }

    /**
     * 兼容旧调用（未降级的用例）
     */
    public CaseResult(String caseId, CodeGenTypeEnum expectedType, CodeGenTypeEnum actualType, Path artifactDir,
                      List<CheckResult> checks, List<String> caseFailures, long durationMs, String error) {
        this(caseId, expectedType, actualType, artifactDir, checks, caseFailures, durationMs, error, false, null);
    }

    /**
     * 路由是否判对
     * <p>
     * 注意它<b>不影响</b> {@link #passed()}：路由判错但产物本身能用，这条用例依然算「产物合格」。
     * 两个指标分开统计，才能看出「路由退化了」和「生成退化了」是两件不同的事。
     */
    public boolean routingCorrect() {
        return expectedType != null && expectedType == actualType;
    }

    /**
     * 真正「模型判错了」的用例：路由结果不对，且不是降级造成的
     * <p>
     * 为什么降级要单独统计（而不是笼统算作路由错）：降级时 {@link #routingCorrect()} 往往是 false，
     * 但那是「模型没跑成」，不是「模型判错了」。实测第一轮基线就撞上这件事 ——
     * 3 条用例里 2 条被 429 打成 HTML，路由准确率显示 33%，看上去像模型很笨，
     * 实际上它一次都没机会回答。混在一起统计，这个指标在限流的日子就完全没用。
     */
    public boolean routingMisjudged() {
        return !routingDegraded && !routingCorrect();
    }

    /**
     * 产物是否合格：没有失败的硬检查项，且用例级断言全过
     * <p>
     * <b>路由正确与否不参与这个判断</b>，理由见 {@link #routingCorrect()}。
     */
    public boolean passed() {
        if (error != null) {
            return false;
        }
        if (!caseFailures.isEmpty()) {
            return false;
        }
        return checks.stream().noneMatch(CheckResult::isBlocking);
    }

    /**
     * 所有失败项的名字，给报告表格用
     */
    public List<String> failureSummary() {
        if (error != null) {
            return List.of("生成失败: " + error);
        }
        List<String> summary = new ArrayList<>();
        checks.stream()
                .filter(CheckResult::isBlocking)
                .forEach(c -> summary.add(c.name() + ": " + c.detail()));
        summary.addAll(caseFailures);
        return List.copyOf(summary);
    }
}
