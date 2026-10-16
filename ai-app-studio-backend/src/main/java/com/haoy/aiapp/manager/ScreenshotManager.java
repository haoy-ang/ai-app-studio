package com.haoy.aiapp.manager;

import com.haoy.aiapp.utils.WebScreenshotUtils;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.WebDriver;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 网页截图调度管理器
 * <p>
 * 解决的问题：{@link WebDriver} 是**有状态**资源，同一个实例不能被多个线程同时使用
 * （线程 A 刚 {@code get("page1")}，线程 B 就 {@code get("page2")}，A 可能截到 page2 的内容）。
 * <p>
 * 设计取舍：
 * <ul>
 *   <li><b>为什么用单线程 worker 而不是 ThreadLocal？</b>
 *       本项目截图跑在虚拟线程里（一次部署一个虚拟线程），ThreadLocal 等价于「每个任务新建一个 driver」，
 *       既没复用又会不断堆积 Chrome 进程。单线程 worker 用任务队列天然保证串行，且只维护一个 driver。</li>
 *   <li><b>为什么不做连接池？</b>截图是低频操作（只有部署时触发一次），池化的复杂度换不来收益。</li>
 *   <li><b>driver 懒初始化</b>：创建失败只影响本次任务，下次会重试（旧写法放在静态块里，一次失败就永久不可用）。</li>
 * </ul>
 *
 * @author <a>程序员haoy</a>
 */
@Component
@Slf4j
public class ScreenshotManager {

    /**
     * 单线程 worker：保证所有截图任务串行执行，driver 只被这一个线程触摸
     */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "screenshot-worker");
        // 守护线程，不阻塞 JVM 退出
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 长期复用的浏览器驱动
     * <p>
     * ⚠️ 只在 {@link #executor} 的线程里访问，保证线程安全。
     */
    private WebDriver webDriver;

    /**
     * 异步生成网页截图
     * <p>
     * 任务会进入单线程队列依次执行，所以调用方可以放心并发提交。
     *
     * @param webUrl 网页URL
     * @return 压缩后的截图文件路径；失败返回 null（与 {@link WebScreenshotUtils#saveWebPageScreenshot} 语义一致）
     */
    public CompletableFuture<String> takeScreenshot(String webUrl) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return WebScreenshotUtils.saveWebPageScreenshot(getOrCreateDriver(), webUrl);
            } catch (Exception e) {
                // 驱动可能已经不可用（进程被外部杀掉等），回收掉，下次任务重建
                log.error("网页截图任务执行失败，URL: {}", webUrl, e);
                recycleDriver();
                return null;
            }
        }, executor);
    }

    /**
     * 获取已有的 driver，没有就创建一个（懒初始化）
     */
    private WebDriver getOrCreateDriver() {
        if (webDriver == null) {
            webDriver = WebScreenshotUtils.initChromeDriver(
                    WebScreenshotUtils.DEFAULT_WIDTH, WebScreenshotUtils.DEFAULT_HEIGHT);
        }
        return webDriver;
    }

    /**
     * 回收 driver
     * <p>
     * 关闭失败也不能抛出去，否则会盖掉调用方真正的业务异常。
     */
    private void recycleDriver() {
        if (webDriver == null) {
            return;
        }
        try {
            webDriver.quit();
        } catch (Exception e) {
            log.warn("关闭 WebDriver 失败：{}", e.getMessage());
        } finally {
            webDriver = null;
        }
    }

    /**
     * 容器关闭时释放资源
     * <p>
     * 这里能真正生效（本类是 Spring Bean），旧写法放在静态工具类上是个永远不会被调用的死方法。
     */
    @PreDestroy
    public void destroy() {
        log.info("ScreenshotManager 开始关闭，正在释放 WebDriver 与截图线程池");
        try {
            // 把 quit 也提交到 worker 线程执行，避免跨线程访问 driver
            executor.submit(this::recycleDriver);
        } catch (RejectedExecutionException e) {
            log.warn("截图线程池已关闭，跳过 WebDriver 回收");
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("截图线程池未能在 10 秒内结束，强制关闭");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
