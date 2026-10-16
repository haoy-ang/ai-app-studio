package com.haoy.aiapp.service.projectdownload;

import jakarta.servlet.http.HttpServletResponse;

/**
 * 项目下载服务接口
 */
public interface ProjectDownloadService {

    /**
     * 将项目打包为 zip 并通过 HTTP 响应下载
     *
     * @param projectPath      项目目录路径
     * @param downloadFileName 下载文件名（不含 .zip 后缀）
     * @param response         HTTP 响应对象
     */
    void downloadProjectAsZip(String projectPath, String downloadFileName, HttpServletResponse response);
}