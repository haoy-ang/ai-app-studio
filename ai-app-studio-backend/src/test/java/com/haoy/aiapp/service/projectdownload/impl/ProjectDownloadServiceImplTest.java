package com.haoy.aiapp.service.projectdownload.impl;

import cn.hutool.core.io.FileUtil;
import com.haoy.aiapp.service.projectdownload.ProjectDownloadService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 项目打包下载测试
 * <p>
 * 重点验证两件事：
 * 1）过滤规则真的挡得住 .env / .env.local 这类敏感文件（精确名匹配挡不住 .env.local）；
 * 2）是「先打包到临时文件、成功后再写响应」，所以失败时响应还没提交。
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
@SpringBootTest
class ProjectDownloadServiceImplTest {

    @Resource
    private ProjectDownloadService projectDownloadService;

    @Test
    void downloadProjectAsZip() throws Exception {
        File projectDir = Files.createTempDirectory("download-test").toFile();
        try {
            // 正常文件
            FileUtil.writeUtf8String("<html></html>", new File(projectDir, "index.html"));
            FileUtil.writeUtf8String("body{}", new File(projectDir, "style.css"));
            // 敏感文件：精确匹配 .env 挡不住 .env.local / .env.production
            FileUtil.writeUtf8String("DB_PASSWORD=123456", new File(projectDir, ".env"));
            FileUtil.writeUtf8String("DB_PASSWORD=123456", new File(projectDir, ".env.local"));
            FileUtil.writeUtf8String("DB_PASSWORD=123456", new File(projectDir, ".env.production"));
            FileUtil.writeUtf8String("SECRET=abc", new File(projectDir, ".npmrc"));
            FileUtil.writeUtf8String("debug log", new File(projectDir, "app.log"));
            // 应被忽略的目录
            FileUtil.mkdir(new File(projectDir, "node_modules"));
            FileUtil.writeUtf8String("module.exports={}", new File(projectDir, "node_modules/index.js"));
            FileUtil.mkdir(new File(projectDir, "dist"));
            FileUtil.writeUtf8String("built", new File(projectDir, "dist/index.html"));

            MockHttpServletResponse response = new MockHttpServletResponse();
            projectDownloadService.downloadProjectAsZip(projectDir.getAbsolutePath(), "测试应用_1024", response);

            Assertions.assertEquals(200, response.getStatus());
            Assertions.assertEquals("application/zip", response.getContentType());

            String contentDisposition = response.getHeader("Content-Disposition");
            log.info("Content-Disposition: {}", contentDisposition);
            Assertions.assertNotNull(contentDisposition);
            // 中文文件名必须同时带上 RFC 5987 的 filename*，否则前端拿到乱码
            Assertions.assertTrue(contentDisposition.contains("filename*=UTF-8''"), "缺少 filename* 参数");

            Set<String> entries = readEntryNames(response.getContentAsByteArray());
            log.info("压缩包内容: {}", entries);

            // 正常文件必须在
            Assertions.assertTrue(entries.contains("index.html"), "index.html 丢了");
            Assertions.assertTrue(entries.contains("style.css"), "style.css 丢了");

            // 敏感文件和忽略目录必须不在
            Assertions.assertFalse(entries.stream().anyMatch(name -> name.contains(".env")),
                    "压缩包里出现了 .env 系列敏感文件: " + entries);
            Assertions.assertFalse(entries.stream().anyMatch(name -> name.startsWith(".npmrc")),
                    "压缩包里出现了 .npmrc");
            Assertions.assertFalse(entries.stream().anyMatch(name -> name.toUpperCase().contains("NODE_MODULES")),
                    "压缩包里出现了 node_modules");
            Assertions.assertFalse(entries.stream().anyMatch(name -> name.startsWith("dist")),
                    "压缩包里出现了 dist");
            Assertions.assertFalse(entries.stream().anyMatch(name -> name.endsWith(".log")),
                    "压缩包里出现了 .log 文件");
        } finally {
            FileUtil.del(projectDir);
        }
    }

    /**
     * 读取 zip 包里的所有条目名
     */
    private Set<String> readEntryNames(byte[] zipBytes) throws Exception {
        Set<String> names = new HashSet<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }
        return names;
    }
}
