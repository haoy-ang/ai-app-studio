package com.haoy.aiapp.eval.inspect;

/**
 * 单个检查项的结果
 * <p>
 * <b>为什么要有 {@link Severity}？</b>因为检查项分两类：一类是「不过就是坏」（文件不在、build 失败），
 * 一类是「看着不太对但能用」（用了占位图服务）。如果只有 passed/failed，第二类会把通过率拖得很难看，
 * 而它其实不该决定一条用例的成败 —— 所以硬检查进分母、软检查只进报告。
 * <p>
 * <b>为什么要有 {@link Status#SKIP}？</b>「跳过」不等于「通过」。把跳过的检查算成通过，会让报告显示
 * 「10/10 通过」，但实际上 build 那条压根没跑 —— 这是自欺。分开显示才诚实。
 *
 * @param name     检查项名称（报告里用它定位，不要用中文，避免报告 diff 时编码问题）
 * @param status   通过 / 失败 / 跳过
 * @param severity 硬 / 软 —— 只有 FAIL + HARD 会让整条用例失败
 * @param detail   补充说明：失败时给「哪里不对」，通过时给「查到了什么」（便于排查误报）
 * @author <a>程序员haoy</a>
 */
public record CheckResult(String name, Status status, Severity severity, String detail) {

    /**
     * 检查项状态
     */
    public enum Status {
        PASS, FAIL, SKIP
    }

    /**
     * 检查项级别
     */
    public enum Severity {
        /**
         * 硬检查：失败即整条用例失败
         */
        HARD,
        /**
         * 软检查：失败只在报告里提示，不影响通过率
         */
        SOFT
    }

    /**
     * 硬检查通过
     */
    public static CheckResult pass(String name, String detail) {
        return new CheckResult(name, Status.PASS, Severity.HARD, detail);
    }

    /**
     * 硬检查失败
     */
    public static CheckResult fail(String name, String detail) {
        return new CheckResult(name, Status.FAIL, Severity.HARD, detail);
    }

    /**
     * 软检查失败（只提示，不算失败）
     */
    public static CheckResult warn(String name, String detail) {
        return new CheckResult(name, Status.FAIL, Severity.SOFT, detail);
    }

    /**
     * 跳过：前置条件不满足，无法判断
     */
    public static CheckResult skip(String name, String detail) {
        return new CheckResult(name, Status.SKIP, Severity.HARD, detail);
    }

    /**
     * 这条检查是否「真的坏了」
     * <p>
     * 注意软检查的 FAIL 不算坏 —— 它只表示「值得看一眼」。
     */
    public boolean isBlocking() {
        return status == Status.FAIL && severity == Severity.HARD;
    }
}
