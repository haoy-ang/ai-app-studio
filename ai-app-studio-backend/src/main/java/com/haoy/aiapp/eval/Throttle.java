package com.haoy.aiapp.eval;

/**
 * 固定间隔节流器 —— 让连续调用按固定节奏排队
 * <p>
 * <b>为什么 L2 必须有它？</b>一轮评估是 24 条用例 × 3 次 = 72 次生成。
 * 模型的限流是按<b>时间窗口内的请求数</b>算的（百炼的 429 {@code limit_requests}、DeepSeek 的并发限制都是），
 * 你连着打，前几次过、后面全 429 —— 而那几次失败会污染通过率，让人误以为是「质量退化了」。
 * <p>
 * <b>为什么用固定间隔而不是令牌桶？</b>评估场景的请求量是已知的（不超过三位数）、
 * 没有「突发」需求，也不需要多机共享额度。固定间隔的实现只有十几行、行为完全可预测 ——
 * 一旦出问题，看日志就能算出「下一次该在几秒后」，而不是去猜桶里还有几个令牌。
 * <p>
 * ⚠️ 它只保证<b>本进程</b>的节奏。如果同时还有人在用这个应用手动生成，两个进程会各自按自己的节奏打，
 * 合起来仍然可能超限。评估应该挑没人在用的时候跑。
 *
 * @author <a>程序员haoy</a>
 */
public final class Throttle {

    private final long intervalMs;

    /**
     * 下一次最早可以执行的时间戳
     */
    private long nextAllowedAt;

    private Throttle(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    /**
     * 按「每 intervalSeconds 秒最多 maxPerInterval 次」换算成固定间隔
     *
     * @param maxPerInterval 时间窗口内允许的次数
     * @param intervalSeconds 时间窗口（秒）
     * @return 节流器；参数非法时退化为不限制
     */
    public static Throttle of(int maxPerInterval, int intervalSeconds) {
        if (maxPerInterval <= 0 || intervalSeconds <= 0) {
            return unlimited();
        }
        long ms = Math.max(1, (long) intervalSeconds * 1000 / maxPerInterval);
        return new Throttle(ms);
    }

    /**
     * 不限制（给「我就是想跑快一点、账号额度管够」的场景）
     */
    public static Throttle unlimited() {
        return new Throttle(0);
    }

    /**
     * 阻塞直到可以执行下一次调用
     * <p>
     * 线程被中断时立即返回而不是抛异常 —— 评估是「能跑完几条算几条」的任务，
     * 因为一个 Ctrl+C 把整轮结果丢掉不划算。调用方读的是中断标记，不是这个方法的返回值。
     */
    public synchronized void acquire() {
        if (intervalMs <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        long waitMs = nextAllowedAt - now;
        if (waitMs > 0) {
            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        nextAllowedAt = Math.max(now, nextAllowedAt) + intervalMs;
    }

    /**
     * 相邻两次调用的最小间隔（毫秒），0 表示不限制
     */
    public long intervalMs() {
        return intervalMs;
    }
}
