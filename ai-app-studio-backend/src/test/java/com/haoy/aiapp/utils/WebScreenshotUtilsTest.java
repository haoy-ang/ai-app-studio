package com.haoy.aiapp.utils;

import cn.hutool.core.io.FileUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.haoy.aiapp.manager.ScreenshotManager;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.File;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * 网页截图测试
 * <p>
 * 注意：会真实启动无头 Chrome 并访问外网，首次运行 WebDriverManager 需要联网下载 chromedriver。
 * 本类同时覆盖三件事：截图能跑通、并发不串页、临时目录能清理。
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
@SpringBootTest
class WebScreenshotUtilsTest {

    @Resource
    private ScreenshotManager screenshotManager;

    /**
     * 并发提交两个截图任务（不同页面），验证：
     * 1）任务被单线程 worker 串行执行，不会因为共用 driver 而截错页面；
     * 2）两个不同页面的截图内容确实不同（如果 driver 被串用，两张图会是同一个页面）。
     */
    @Test
    void takeScreenshotConcurrently() {
        String urlA = "https://www.baidu.com";
        String urlB = "https://www.qq.com";

        CompletableFuture<String> futureA = screenshotManager.takeScreenshot(urlA);
        CompletableFuture<String> futureB = screenshotManager.takeScreenshot(urlB);

        String pathA = futureA.join();
        String pathB = futureB.join();
        log.info("并发截图结果 A: {}, B: {}", pathA, pathB);

        Assertions.assertNotNull(pathA, "A 页面截图失败");
        Assertions.assertNotNull(pathB, "B 页面截图失败");
        Assertions.assertTrue(new File(pathA).exists());
        Assertions.assertTrue(new File(pathB).exists());

        // 两个页面内容不同，截图文件的 MD5 必须不同
        String md5A = DigestUtil.md5Hex(new File(pathA));
        String md5B = DigestUtil.md5Hex(new File(pathB));
        Assertions.assertNotEquals(md5A, md5B, "两个不同页面截出了一样的图，说明 WebDriver 被串用了");
    }

    /**
     * 验证过期的临时截图目录会被清理
     */
    @Test
    void cleanupTempFiles() {
        File rootDir = new File(WebScreenshotUtils.getTempScreenshotDir());
        File expiredDir = new File(rootDir, "unit-test-expired");
        FileUtil.mkdir(expiredDir);
        Assertions.assertTrue(expiredDir.exists());

        // TTL 传 0 表示「比现在早的目录都算过期」
        int deleted = WebScreenshotUtils.cleanupTempFiles(Duration.ZERO);
        log.info("清理了 {} 个临时目录", deleted);
        Assertions.assertFalse(expiredDir.exists(), "过期的临时目录没有被清理");
    }
}
