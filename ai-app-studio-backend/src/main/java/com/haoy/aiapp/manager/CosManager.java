package com.haoy.aiapp.manager;

import com.haoy.aiapp.config.CosClientConfig;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.PutObjectResult;
import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.InputStream;

/**
 * COS对象存储管理器
 *
 * @author <a>程序员haoy</a>
 */
@Component
@Slf4j
public class CosManager {

    @Resource
    private CosClientConfig cosClientConfig;

    @Resource
    private COSClient cosClient;

    /**
     * 上传对象
     *
     * @param key  唯一键
     * @param file 文件
     * @return 上传结果
     */
    public PutObjectResult putObject(String key, File file) {
        PutObjectRequest putObjectRequest = new PutObjectRequest(cosClientConfig.getBucket(), key, file);
        return cosClient.putObject(putObjectRequest);
    }

    /**
     * 上传文件到 COS 并返回访问 URL
     *
     * @param key  COS对象键(完整路径)
     * @param file 要上传的文件
     * @return 文件的访问URL,失败返回null
     */
    public String uploadFile(String key, File file) {
        // 上传文件
        PutObjectResult result = putObject(key, file);
        if (result != null) {
            // 构建访问URL
            String url = String.format("%s%s", cosClientConfig.getHost(), key);
            log.info("文件上传COS成功:{}->{}", file.getName(), url);
            return url;
        } else {
            log.error("文件上传COS失败,返回结果为空");
            return null;
        }
    }

    /**
     * 通过输入流上传对象并返回访问 URL（用于头像这类小文件）
     * <p>
     * 相比 {@link #uploadFile(String, File)}，少了两步：不用先把上传流落成临时文件，
     * 也能顺手设置 Content-Type 与缓存头 —— 头像不加缓存头的话，换新头像后
     * 前端 URL 变了一次就能立刻生效（本方法内部还给对象键留了时间戳的口子）。
     *
     * @param key         对象键（完整路径）
     * @param inputStream 输入流
     * @param size        内容长度（字节），COS 需要它才能正确设置 Content-Length
     * @param contentType MIME 类型，如 image/png
     * @return 文件的访问 URL，失败返回 null
     */
    public String uploadFile(String key, InputStream inputStream, long size, String contentType) {
        try {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(size);
            if (StrUtil.isNotBlank(contentType)) {
                metadata.setContentType(contentType);
            }
            // 头像是不变资源，加一天缓存，减少重复下载
            metadata.setCacheControl("max-age=86400");
            cosClient.putObject(cosClientConfig.getBucket(), key, inputStream, metadata);
            String url = String.format("%s%s", cosClientConfig.getHost(), key);
            log.info("文件流式上传COS成功:{}->{}", key, url);
            return url;
        } catch (Exception e) {
            // 这里不抛异常，保持和 uploadFile(String, File) 一致的「返回 null 表示失败」契约，
            // 由调用方决定怎么提示用户，避免底层 SDK 异常直接冒到接口层变成「系统错误」
            log.error("文件流式上传COS失败:{}", key, e);
            return null;
        }
    }

    /**
     * 删除对象
     * <p>
     * 删除失败只记日志不抛异常：调用方（如删除应用）不应该因为清理文件失败而中断主流程。
     *
     * @param key 对象键（完整路径）
     * @return 是否删除成功
     */
    public boolean deleteObject(String key) {
        if (StrUtil.isBlank(key)) {
            return false;
        }
        try {
            cosClient.deleteObject(cosClientConfig.getBucket(), key);
            log.info("COS 对象删除成功:{}", key);
            return true;
        } catch (Exception e) {
            log.error("COS 对象删除失败:{}", key, e);
            return false;
        }
    }

    /**
     * 根据访问 URL 删除对象
     * <p>
     * ⚠️ 只删**本桶**的文件：{@code cover} 字段管理员可以手填任意外链，
     * 不做前缀校验就可能去删别人的资源。
     *
     * @param url 文件访问URL
     * @return 是否执行了删除（不是本桶的 URL 会直接跳过并返回 false）
     */
    public boolean deleteFileByUrl(String url) {
        if (StrUtil.isBlank(url)) {
            return false;
        }
        String host = cosClientConfig.getHost();
        if (StrUtil.isBlank(host) || !url.startsWith(host)) {
            log.info("该文件不属于本 COS 桶，跳过删除:{}", url);
            return false;
        }
        // 去掉 host 前缀就是对象键
        String key = url.substring(host.length());
        return deleteObject(key);
    }
}