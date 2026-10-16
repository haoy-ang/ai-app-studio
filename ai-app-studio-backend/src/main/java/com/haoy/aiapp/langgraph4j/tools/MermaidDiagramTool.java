package com.haoy.aiapp.langgraph4j.tools;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.haoy.aiapp.langgraph4j.model.ImageCategoryEnum;
import com.haoy.aiapp.langgraph4j.model.ImageResource;
import com.haoy.aiapp.manager.CosManager;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 架构图绘制工具 —— 把 AI 写的 Mermaid 代码渲染成图片
 * <p>
 * 架构图是需要按描述定制的，没法上网搜，所以思路是「AI 生成 Mermaid 文本 → 渲染成图」。
 * <p>
 * <b>两条渲染路线（可配置）：</b>
 * <ol>
 *   <li><b>本地 mmdc（主）</b>：调 mermaid-cli 把文本渲染成 SVG，再上传 COS。
 *       优点是完全可控、离线可用、没有 URL 长度限制。</li>
 *   <li><b>mermaid.ink（兜底）</b>：mmdc 不可用时改用在线渲染服务。
 *       实测 1.3 秒出图，但它走 GET、Mermaid 代码要 base64 进 URL，大图有长度风险，
 *       所以只当兜底 —— 而且拿到图后会转存到自己的 COS，不依赖它的临时地址。</li>
 * </ol>
 * <p>
 * ⚠️ 本项目把 mermaid-cli 装在项目目录下（而非全局），所以命令走配置项 {@code mermaid.mmdc-command}，
 * 并用 {@code mermaid.work-dir} 作为相对路径基准。
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
@Component
public class MermaidDiagramTool {

    /**
     * mermaid.ink 的在线渲染入口：GET + base64url(代码)
     */
    private static final String MERMAID_INK_URL_TEMPLATE = "https://mermaid.ink/img/";

    @Resource
    private CosManager cosManager;

    /**
     * 渲染命令（空白分隔），例如：{@code node xxx/cli.js}；留空则直接走在线兜底
     */
    @Value("${mermaid.mmdc-command:}")
    private String mmdcCommand;

    /**
     * puppeteer 配置文件路径 —— 里面指定了本机 Chrome，避免 mermaid-cli 再下载一份 Chromium
     */
    @Value("${mermaid.puppeteer-config:}")
    private String puppeteerConfig;

    /**
     * 相对路径的解析基准目录；为空时取当前运行目录
     */
    @Value("${mermaid.work-dir:}")
    private String workDir;

    /**
     * mmdc 失败时是否走 mermaid.ink 在线兜底
     */
    @Value("${mermaid.ink-fallback:true}")
    private boolean inkFallback;

    /**
     * 单次渲染的超时秒数
     */
    @Value("${mermaid.timeout-seconds:90}")
    private int timeoutSeconds;

    /**
     * 将 Mermaid 代码转换为架构图图片，用于展示系统结构和技术关系
     *
     * @param mermaidCode Mermaid 图表代码
     * @param description 架构图描述（会写进提示词告诉 AI 这张图的用途）
     * @return 图片资源列表；失败返回空列表
     */
    @Tool("将 Mermaid 代码转换为架构图图片，用于展示系统结构和技术关系")
    public List<ImageResource> generateArchitectureDiagram(@P("Mermaid 图表代码，必须是合法的 mermaid 语法") String mermaidCode,
                                                           @P("架构图用途描述") String description) {
        if (StrUtil.isBlank(mermaidCode)) {
            return new ArrayList<>();
        }
        File diagramFile = null;
        try {
            diagramFile = renderByMmdc(mermaidCode);
            if (diagramFile == null && inkFallback) {
                log.info("本地 mmdc 不可用，改用 mermaid.ink 在线渲染兜底");
                diagramFile = renderByMermaidInk(mermaidCode);
            }
            if (diagramFile == null) {
                log.warn("架构图渲染失败，两条路线都不可用");
                return new ArrayList<>();
            }
            // 上传到自己的对象存储：拿到稳定、短的地址，不依赖第三方临时链接
            String keyName = String.format("/mermaid/%s/%s", RandomUtil.randomString(5), diagramFile.getName());
            String cosUrl = cosManager.uploadFile(keyName, diagramFile);
            if (StrUtil.isBlank(cosUrl)) {
                log.warn("架构图上传 COS 失败");
                return new ArrayList<>();
            }
            return Collections.singletonList(ImageResource.builder()
                    .category(ImageCategoryEnum.ARCHITECTURE)
                    .description(description)
                    .url(cosUrl)
                    .build());
        } catch (Exception e) {
            // 架构图属于增强内容，失败不能影响整个工作流
            log.error("生成架构图失败：{}", e.getMessage());
            return new ArrayList<>();
        } finally {
            if (diagramFile != null) {
                FileUtil.del(diagramFile);
            }
        }
    }

    /**
     * 用本地 mermaid-cli 渲染成 SVG
     *
     * @return 渲染产出的临时文件；命令未配置或执行失败返回 null
     */
    private File renderByMmdc(String mermaidCode) {
        if (StrUtil.isBlank(mmdcCommand)) {
            log.info("未配置 mermaid.mmdc-command，跳过本地渲染");
            return null;
        }
        File tempInput = null;
        File tempOutput = null;
        try {
            tempInput = FileUtil.createTempFile("mermaid_input_", ".mmd", true);
            FileUtil.writeUtf8String(mermaidCode, tempInput);
            tempOutput = FileUtil.createTempFile("mermaid_output_", ".svg", true);

            List<String> tokens = new ArrayList<>(StrUtil.splitTrim(mmdcCommand, ' '));
            tokens.add("-p");
            tokens.add(puppeteerConfig);
            tokens.add("-i");
            tokens.add(tempInput.getAbsolutePath());
            tokens.add("-o");
            tokens.add(tempOutput.getAbsolutePath());
            tokens.add("-b");
            tokens.add("transparent");

            File baseDir = StrUtil.isBlank(workDir)
                    ? new File(System.getProperty("user.dir"))
                    : new File(workDir);
            ProcessBuilder processBuilder = new ProcessBuilder(tokens)
                    // 合并错误流，避免子进程写满管道缓冲区导致卡死
                    .redirectErrorStream(true)
                    .directory(baseDir);
            Process process = processBuilder.start();
            process.getOutputStream().close();

            String output;
            try (InputStream inputStream = process.getInputStream()) {
                // 必须边跑边把输出读干净，否则子进程可能因为管道写满而永远卡住
                output = IoUtil.read(inputStream, StandardCharsets.UTF_8);
            }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("mmdc 执行超时（{} 秒）", timeoutSeconds);
                return null;
            }
            if (process.exitValue() != 0) {
                log.warn("mmdc 退出码 {}，输出：{}", process.exitValue(), StrUtil.maxLength(output, 500));
                return null;
            }
            if (!tempOutput.exists() || tempOutput.length() == 0) {
                log.warn("mmdc 没有产出文件，输出：{}", StrUtil.maxLength(output, 500));
                return null;
            }
            log.info("mmdc 渲染成功，SVG 大小 {} 字节", tempOutput.length());
            return tempOutput;
        } catch (Exception e) {
            log.warn("mmdc 渲染异常：{}", e.getMessage());
            return null;
        } finally {
            // 输入文件用完即删，输出文件留给调用方上传后再删
            if (tempInput != null) {
                FileUtil.del(tempInput);
            }
        }
    }

    /**
     * 用 mermaid.ink 在线渲染兜底
     *
     * @return 下载到本地的临时文件；失败返回 null
     */
    private File renderByMermaidInk(String mermaidCode) {
        File tempOutput = null;
        try {
            String encoded = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(mermaidCode.getBytes(StandardCharsets.UTF_8));
            try (HttpResponse response = HttpRequest.get(MERMAID_INK_URL_TEMPLATE + encoded)
                    .header("User-Agent", "Mozilla/5.0")
                    .timeout(timeoutSeconds * 1000)
                    .execute()) {
                if (!response.isOk()) {
                    log.warn("mermaid.ink 返回非 200：{}", response.getStatus());
                    return null;
                }
                byte[] bytes = response.bodyBytes();
                if (bytes == null || bytes.length == 0) {
                    return null;
                }
                tempOutput = FileUtil.createTempFile("mermaid_ink_", ".jpg", true);
                FileUtil.writeBytes(bytes, tempOutput);
                log.info("mermaid.ink 渲染成功，大小 {} 字节", bytes.length);
                return tempOutput;
            }
        } catch (Exception e) {
            log.warn("mermaid.ink 渲染失败：{}", e.getMessage());
            if (tempOutput != null) {
                FileUtil.del(tempOutput);
            }
            return null;
        }
    }
}
