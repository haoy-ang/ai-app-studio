package com.haoy.aiapp.utils;

import cn.hutool.core.img.ImgUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.haoy.aiapp.exception.BusinessException;
import com.haoy.aiapp.exception.ErrorCode;
import io.github.bonigarcia.wdm.WebDriverManager;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.File;
import java.time.Duration;
import java.util.UUID;

/**
 * 网页截图工具类
 * <p>
 * 只提供**无状态**的能力：创建 driver、截图、清理临时目录。
 * driver 的持有、复用与并发控制交给 {@link com.haoy.aiapp.manager.ScreenshotManager}。
 * <p>
 * 为什么不做成「静态持有 driver」？有三个坑：
 * <ol>
 *   <li>静态单例被多线程共用时，{@code get(url)} 会互相覆盖，可能截到别人的页面；</li>
 *   <li>静态字段上的 {@code @PreDestroy} 永远不会执行（这个类不是 Spring Bean），Chrome 进程会残留；</li>
 *   <li>静态代码块里初始化一旦失败，JVM 会把该类标记为初始化失败，**同一个类加载器内永不重试**，
 *       一次网络抖动就能让截图功能永久不可用。</li>
 * </ol>
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
public class WebScreenshotUtils {

    /**
     * 默认窗口宽度
     */
    public static final int DEFAULT_WIDTH = 1600;

    /**
     * 默认窗口高度
     */
    public static final int DEFAULT_HEIGHT = 900;

    /**
     * 截图临时目录（相对于进程工作目录）
     */
    private static final String TEMP_SCREENSHOT_DIR =
            System.getProperty("user.dir") + File.separator + "tmp" + File.separator + "screenshots";

    /**
     * 初始化 Chrome 浏览器驱动
     * <p>
     * 由调用方决定何时创建，所以初始化失败只影响这一次调用，下次可以重试。
     */
    public static WebDriver initChromeDriver(int width, int height) {
        try {
            // 自动管理 ChromeDriver
            WebDriverManager.chromedriver().setup();
            // 配置 Chrome 选项
            ChromeOptions options = new ChromeOptions();
            // 无头模式
            options.addArguments("--headless");
            // 禁用GPU(在某些环境下避免问题)
            options.addArguments("--disable-gpu");
            // 禁用沙盒模式(Docker环境需要)
            options.addArguments("--no-sandbox");
            // 禁用开发者shm使用
            options.addArguments("--disable-dev-shm-usage");
            // 设置窗口大小
            options.addArguments(String.format("--window-size=%d,%d", width, height));
            // 禁用扩展
            options.addArguments("--disable-extensions");
            // 设置用户代理
            options.addArguments("--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
            // 创建驱动
            WebDriver driver = new ChromeDriver(options);
            // 设置页面加载超时
            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30));
            // 设置隐式等待
            driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10));
            log.info("Chrome WebDriver 初始化成功，窗口大小: {}x{}", width, height);
            return driver;
        } catch (Exception e) {
            log.error("初始化 Chrome 浏览器失败", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "初始化 Chrome 浏览器失败");
        }
    }

    /**
     * 初始化 Chrome 浏览器驱动（使用默认窗口大小）
     */
    public static WebDriver initChromeDriver() {
        return initChromeDriver(DEFAULT_WIDTH, DEFAULT_HEIGHT);
    }

    /**
     * 保存图片到文件
     */
    private static void saveImage(byte[] imageBytes, String imagePath) {
        try {
            FileUtil.writeBytes(imageBytes, imagePath);
        } catch (Exception e) {
            log.error("保存图片失败:{}", imagePath, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "保存图片失败");
        }
    }

    /**
     * 压缩图片
     */
    private static void compressImage(String originalImagePath, String compressedImagePath) {
        // 压缩图片质量(0.1=10%质量)
        final float COMPRESSION_QUALITY = 0.3f;
        try {
            ImgUtil.compress(
                    FileUtil.file(originalImagePath),
                    FileUtil.file(compressedImagePath),
                    COMPRESSION_QUALITY
            );
        } catch (Exception e) {
            log.error("压缩图片失败:{}->{}", originalImagePath, compressedImagePath, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "压缩图片失败");
        }
    }

    /**
     * 等待页面加载完成
     */
    private static void waitForPageLoad(WebDriver driver) {
        try {
            // 创建等待页面加载对象
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
            // 等待 document.readyState 为 complete
            wait.until(webDriver ->
                    ((JavascriptExecutor) webDriver).executeScript("return document.readyState")
                            .equals("complete")
            );
            // 额外等待一段时间,确保动态内容加载完成
            Thread.sleep(2000);
            log.info("页面加载完成");
        } catch (Exception e) {
            log.error("等待页面加载时出现异常,继续执行截图", e);
        }
    }

    /**
     * 生成网页截图
     * <p>
     * 无状态方法：driver 由调用方传入并负责生命周期，所以本方法本身不持有可变状态。
     * 注意 driver 是**有状态**的，同一时刻只能被一个线程使用，并发控制在外层
     * （{@link com.haoy.aiapp.manager.ScreenshotManager} 用单线程 worker 保证串行）。
     *
     * @param driver 浏览器驱动
     * @param webUrl 网页URL
     * @return 压缩后的截图文件路径，失败返回null
     */
    public static String saveWebPageScreenshot(WebDriver driver, String webUrl) {
        if (StrUtil.isBlank(webUrl)) {
            log.error("网页URL不能为空");
            return null;
        }
        if (driver == null) {
            log.error("WebDriver 不能为空");
            return null;
        }
        // 图片后缀
        final String IMAGE_SUFFIX = ".png";
        // 压缩图片后缀
        final String COMPRESSION_SUFFIX = "_compressed.jpg";
        // 本次截图的独立临时目录
        String rootPath = TEMP_SCREENSHOT_DIR + File.separator + UUID.randomUUID().toString().substring(0, 8);
        try {
            // 创建临时目录
            FileUtil.mkdir(rootPath);
            // 原始截图文件路径
            String imageSavePath = rootPath + File.separator + RandomUtil.randomNumbers(5) + IMAGE_SUFFIX;
            // 访问网页
            driver.get(webUrl);
            // 等待页面加载完成
            waitForPageLoad(driver);
            // 截图
            byte[] screenshotBytes = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
            // 保存原始图片
            saveImage(screenshotBytes, imageSavePath);
            log.info("原始截图保存成功:{}", imageSavePath);
            // 压缩图片
            String compressedImagePath = rootPath + File.separator + RandomUtil.randomNumbers(5) + COMPRESSION_SUFFIX;
            compressImage(imageSavePath, compressedImagePath);
            log.info("压缩图片保存成功:{}", compressedImagePath);
            // 删除原始图片,只保留压缩图片
            FileUtil.del(imageSavePath);
            return compressedImagePath;
        } catch (Exception e) {
            log.error("网页截图失败:{}", webUrl, e);
            // 失败时顺手删掉本次创建的临时目录，避免留下无主垃圾
            FileUtil.del(rootPath);
            return null;
        }
    }

    /**
     * 清理过期的临时截图目录
     * <p>
     * 截图成功后单次目录会被清理，但根目录本身、以及截图失败时残留的目录需要定时任务兜底。
     *
     * @param ttl 存活时长，最后修改时间早于 now - ttl 的目录会被删除
     * @return 删除的目录数量
     */
    public static int cleanupTempFiles(Duration ttl) {
        if (ttl == null) {
            return 0;
        }
        File rootDir = new File(TEMP_SCREENSHOT_DIR);
        File[] dirs = rootDir.listFiles(File::isDirectory);
        if (dirs == null || dirs.length == 0) {
            return 0;
        }
        long deadline = System.currentTimeMillis() - ttl.toMillis();
        int deleted = 0;
        for (File dir : dirs) {
            if (dir.lastModified() < deadline && FileUtil.del(dir)) {
                deleted++;
            }
        }
        if (deleted > 0) {
            log.info("清理过期临时截图目录完成，共清理 {} 个", deleted);
        }
        return deleted;
    }

    /**
     * 获取截图临时根目录（供定时任务或排查问题时使用）
     */
    public static String getTempScreenshotDir() {
        return TEMP_SCREENSHOT_DIR;
    }
}
