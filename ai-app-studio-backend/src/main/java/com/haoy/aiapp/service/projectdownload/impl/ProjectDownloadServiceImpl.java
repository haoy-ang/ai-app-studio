package com.haoy.aiapp.service.projectdownload.impl;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.ZipUtil;
import com.haoy.aiapp.exception.BusinessException;
import com.haoy.aiapp.exception.ErrorCode;
import com.haoy.aiapp.exception.ThrowUtils;
import com.haoy.aiapp.service.projectdownload.ProjectDownloadService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileFilter;
import java.io.FileOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

@Service
@Slf4j
public class ProjectDownloadServiceImpl implements ProjectDownloadService {

    /**
     * 需要过滤的文件和目录名称（精确匹配）
     */
    private static final Set<String> IGNORED_NAMES = Set.of(
            "node_modules",
            ".git",
            "dist",
            "build",
            ".ds_store",
            "target",
            ".mvn",
            ".idea",
            ".vscode",
            ".next",
            ".nuxt",
            "coverage"
    );

    /**
     * 需要过滤的文件名前缀
     * <p>
     * ⚠️ 这里必须用前缀匹配而不是精确匹配：`.env` 精确匹配只能挡住 `.env` 本身，
     * 挡不住 `.env.local` / `.env.production` —— 而那里面通常就是数据库密码和 API key。
     */
    private static final Set<String> IGNORED_PREFIXES = Set.of(
            ".env",
            ".npmrc"
    );

    /**
     * 需要过滤的文件扩展名
     */
    private static final Set<String> IGNORED_EXTENSIONS = Set.of(
            ".log",
            ".tmp",
            ".cache",
            ".pem",
            ".key"
    );

    /**
     * 检查路径是否允许包含在压缩包中
     *
     * @param projectRoot 项目根目录
     * @param fullPath    完整路径
     * @return 是否允许
     */
    private boolean isPathAllowed(Path projectRoot, Path fullPath) {
        // 获取相对路径
        Path relativePath = projectRoot.relativize(fullPath);
        // 检查路径中的每一部分
        for (Path part : relativePath) {
            // 统一转小写比较（Windows 文件系统本身大小写不敏感，避免 Node_ENV 这类绕过）
            String partName = part.toString().toLowerCase(Locale.ROOT);
            // 检查是否在忽略名称列表中
            if (IGNORED_NAMES.contains(partName)) {
                return false;
            }
            // 检查文件名前缀
            if (IGNORED_PREFIXES.stream().anyMatch(partName::startsWith)) {
                return false;
            }
            // 检查文件扩展名
            if (IGNORED_EXTENSIONS.stream().anyMatch(partName::endsWith)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void downloadProjectAsZip(String projectPath, String downloadFileName, HttpServletResponse response) {
        // 基础校验
        ThrowUtils.throwIf(StrUtil.isBlank(projectPath), ErrorCode.PARAMS_ERROR, "项目路径不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(downloadFileName), ErrorCode.PARAMS_ERROR, "下载文件名不能为空");
        File projectDir = new File(projectPath);
        ThrowUtils.throwIf(!projectDir.exists(), ErrorCode.NOT_FOUND_ERROR, "项目目录不存在");
        ThrowUtils.throwIf(!projectDir.isDirectory(), ErrorCode.PARAMS_ERROR, "指定路径不是目录");
        log.info("开始打包下载项目:{}->{}.zip", projectPath, downloadFileName);
        // 定义文件过滤器
        FileFilter filter = file -> isPathAllowed(projectDir.toPath(), file.toPath());
        File tempZip = null;
        try {
            // 1. 先打包到临时文件
            // ⚠️ 不能直接往 response.getOutputStream() 里写：HTTP 响应一旦开始写 body 就 committed，
            // 之后再抛异常，GlobalExceptionHandler 往已提交的响应里塞 JSON 就会拼出「zip 头 + JSON 尾巴」的坏包。
            tempZip = File.createTempFile("app-download-", ".zip");
            try (FileOutputStream fos = new FileOutputStream(tempZip)) {
                ZipUtil.zip(fos, StandardCharsets.UTF_8, false, filter, projectDir);
            }
            // 2. 打包成功后才写响应头和响应体
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/zip");
            response.setHeader("Content-Disposition", buildContentDisposition(downloadFileName));
            response.setContentLengthLong(tempZip.length());
            FileUtil.writeToStream(tempZip, response.getOutputStream());
            response.flushBuffer();
            log.info("项目打包下载完成:{}，大小: {} 字节", downloadFileName, tempZip.length());
        } catch (Exception e) {
            log.error("项目打包下载异常:{}", projectPath, e);
            // 此时响应还未提交（或已由容器关闭），异常可以正常交给全局异常处理器
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "项目打包下载失败");
        } finally {
            // 临时文件必须删，避免磁盘泄漏
            if (tempZip != null) {
                FileUtil.del(tempZip);
            }
        }
    }

    /**
     * 构建 Content-Disposition 响应头
     * <p>
     * 同时给出 {@code filename}（老浏览器）和 {@code filename*}（RFC 5987，现代浏览器），
     * 这样中文文件名也能正确显示，不会变成乱码或被丢弃。
     */
    private String buildContentDisposition(String downloadFileName) {
        String fullName = downloadFileName + ".zip";
        String encoded = URLEncoder.encode(fullName, StandardCharsets.UTF_8).replace("+", "%20");
        return String.format("attachment; filename=\"%s\"; filename*=UTF-8''%s", encoded, encoded);
    }
}