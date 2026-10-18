package com.haoy.aiapp.service.screen.impl;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.haoy.aiapp.exception.BusinessException;
import com.haoy.aiapp.exception.ErrorCode;
import com.haoy.aiapp.exception.ThrowUtils;
import com.haoy.aiapp.manager.CosManager;
import com.haoy.aiapp.manager.ScreenshotManager;
import com.haoy.aiapp.service.screen.ScreenshotService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
@Slf4j
public class ScreenshotServiceImpl implements ScreenshotService {

    @Resource
    private CosManager cosManager;

    @Resource
    private ScreenshotManager screenshotManager;

    @Override
    public String generateAndUploadScreenshot(String webUrl) {
        ThrowUtils.throwIf(StrUtil.isBlank(webUrl), ErrorCode.PARAMS_ERROR, "网页URL不能为空");
        log.info("开始生成网页截图,URL:{}", webUrl);
        // 0. 先探一下这个地址是不是真能打开
        //    不加这一步的话，两类问题都会被同一句话盖住：
        //    a) 部署地址根本连不上（典型原因：本地 Nginx 没启动）→ WebDriver 抛 ERR_CONNECTION_REFUSED，
        //       上层只能看到「本地截图生成失败」，得翻三层日志才知道是 Nginx 的问题；
        //    b) 连得上但返回 404（Nginx 的 root 与 app.deploy.root-dir 不一致）→ 更坏，
        //       Chrome 会老老实实把 nginx 的 404 页面截下来当封面，全程不报错。
        //    探测只花几毫秒，比启动一次 Chrome（约 2 秒 + 可能等满页面加载超时）便宜得多。
        assertReachable(webUrl);
        // 1. 生成本地截图（交给 ScreenshotManager 串行调度，内部复用同一个 WebDriver）
        String localScreenshotPath = screenshotManager.takeScreenshot(webUrl).join();
        ThrowUtils.throwIf(StrUtil.isBlank(localScreenshotPath), ErrorCode.OPERATION_ERROR, "本地截图生成失败");
        try {
            // 2. 上传到对象存储
            String cosUrl = uploadScreenshotToCos(localScreenshotPath);
            ThrowUtils.throwIf(StrUtil.isBlank(cosUrl), ErrorCode.OPERATION_ERROR, "截图上传对象存储失败");
            log.info("网页截图生成并上传成功:{}->{}", webUrl, cosUrl);
            return cosUrl;
        } finally {
            // 3. 清理本地文件
            cleanupLocalFile(localScreenshotPath);
        }
    }

    /**
     * 探测部署地址是否可访问（非 2xx 也算不可访问）
     * <p>
     * 只取响应状态码、不读 body：截图的目的是「页面能打开」，body 由 Chrome 自己去渲染。
     *
     * @param webUrl 部署地址
     */
    static void assertReachable(String webUrl) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(webUrl))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        int status;
        try {
            status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException e) {
            // 连接被拒 / 域名解析失败 / 超时都走这里
            throw new BusinessException(ErrorCode.OPERATION_ERROR, String.format(
                    "部署地址无法连接：%s（%s）。请确认本地 Nginx 已启动，且监听端口与 app.deploy.host 一致",
                    webUrl, e.getClass().getSimpleName()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "探测部署地址时被中断：" + webUrl);
        }
        if (status < 200 || status >= 300) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, String.format(
                    "部署地址返回 HTTP %d：%s。请检查 Nginx 的 root 是否与 app.deploy.root-dir 完全一致",
                    status, webUrl));
        }
    }

    /**
     * 上传截图到对象存储
     *
     * @param localScreenshotPath 本地截图路径
     * @return 对象存储访问URL,失败返回null
     */
    private String uploadScreenshotToCos(String localScreenshotPath) {
        if (StrUtil.isBlank(localScreenshotPath)) {
            return null;
        }
        File screenshotFile = new File(localScreenshotPath);
        if (!screenshotFile.exists()) {
            log.error("截图文件不存在:{}", localScreenshotPath);
            return null;
        }
        // 生成 COS 对象键
        String fileName = UUID.randomUUID().toString().substring(0, 8) + "_compressed.jpg";
        String coskey = generateScreenshotKey(fileName);
        return cosManager.uploadFile(coskey, screenshotFile);
    }

    /**
     * 生成截图的对象存储键
     * 格式:/screenshots/2025/07/31/filename.jpg
     */
    private String generateScreenshotKey(String fileName) {
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        return String.format("/screenshots/%s/%s", datePath, fileName);
    }

    /**
     * 清理本地文件
     *
     * @param localFilePath 本地文件路径
     */
    private void cleanupLocalFile(String localFilePath) {
        File localFile = new File(localFilePath);
        if (localFile.exists()) {
            File parentDir = localFile.getParentFile();
            FileUtil.del(parentDir);
            log.info("本地截图文件已清理:{}", localFilePath);
        }
    }
}