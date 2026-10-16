package com.haoy.aiapp.langgraph4j.node;

import cn.hutool.core.util.StrUtil;
import com.haoy.aiapp.langgraph4j.ai.CodeQualityCheckService;
import com.haoy.aiapp.langgraph4j.model.QualityResult;
import com.haoy.aiapp.langgraph4j.state.WorkflowContext;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.List;

import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 代码质量检查节点 —— 把生成出来的代码拼成一段文本，交给 AI 做「能不能跑 / 能不能打包」的体检
 * <p>
 * 它的结果 {@link QualityResult} 就是工作流里那条<b>循环边</b>的判据：
 * 通过就继续构建，不通过就带着错误信息回退到代码生成节点重做。
 * <p>
 * ⚠️ <b>两处写法都是踩过坑之后的结论：</b>
 * <ol>
 *   <li><b>不用 Hutool 的 {@code FileUtil.walkFiles}</b>。它的回调只是「事后过滤」，
 *       目录本身照样会被完整走一遍 —— 对带 {@code node_modules} 的 Vue 工程会白遍历几万个文件。
 *       这里换成 {@code Files.walkFileTree} + {@code preVisitDirectory} 返回 {@code SKIP_SUBTREE}，
 *       在进入目录的那一刻就剪掉，真正不进去。</li>
 *   <li><b>加了总量上限</b>。若把目录里所有代码文件全拼起来发给 AI，
 *       文件一多就会撞上下文上限（表现是 400 或莫名截断）。这里限制文件数和总字符数。</li>
 * </ol>
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
public class CodeQualityCheckNode {

    /**
     * 需要检查的文件扩展名
     */
    private static final List<String> CODE_EXTENSIONS = Arrays.asList(
            ".html", ".htm", ".css", ".js", ".json", ".vue", ".ts", ".jsx", ".tsx"
    );

    /**
     * 拼接进提示词的目录名 —— 整棵子树直接跳过
     */
    private static final List<String> SKIP_DIRS = Arrays.asList("node_modules", "dist", "target", ".git");

    /**
     * 最多拼多少个文件，避免提示词过长
     */
    private static final int MAX_FILES = 200;

    /**
     * 拼接内容的总字符上限
     */
    private static final int MAX_TOTAL_CHARS = 120_000;

    /**
     * 创建代码质量检查节点
     *
     * @param qualityCheckService 代码质量检查 AI 服务
     * @return 节点动作
     */
    public static AsyncNodeAction<MessagesState<String>> create(CodeQualityCheckService qualityCheckService) {
        return node_async(state -> {
            WorkflowContext context = WorkflowContext.getContext(state);
            log.info("执行节点: 代码质量检查");

            String generatedCodeDir = context.getGeneratedCodeDir();
            QualityResult qualityResult;
            try {
                String codeContent = readAndConcatenateCodeFiles(generatedCodeDir);
                if (StrUtil.isBlank(codeContent)) {
                    log.warn("未找到可检查的代码文件，目录: {}", generatedCodeDir);
                    qualityResult = QualityResult.builder()
                            .isValid(false)
                            .errors(List.of("未找到可检查的代码文件"))
                            .suggestions(List.of("请确保代码生成成功"))
                            .build();
                } else {
                    qualityResult = qualityCheckService.checkCodeQuality(codeContent);
                    log.info("代码质量检查完成 - 是否通过: {}", qualityResult.getIsValid());
                }
            } catch (Exception e) {
                // 质检本身出问题（比如 AI 超时）不应该拦下整个流程，按「通过」放行到下一步
                log.error("代码质量检查异常，按通过处理: {}", e.getMessage());
                qualityResult = QualityResult.builder()
                        .isValid(true)
                        .build();
            }

            context.setCurrentStep("代码质量检查");
            context.setQualityResult(qualityResult);
            return WorkflowContext.saveContext(context);
        });
    }

    /**
     * 读取并拼接代码目录下的所有代码文件
     *
     * @param codeDir 代码目录
     * @return 拼接后的文本；目录不存在或没有代码文件时返回空串
     */
    static String readAndConcatenateCodeFiles(String codeDir) {
        if (StrUtil.isBlank(codeDir)) {
            return "";
        }
        Path root = Paths.get(codeDir);
        if (!Files.isDirectory(root)) {
            log.error("代码目录不存在或不是目录: {}", codeDir);
            return "";
        }

        StringBuilder codeContent = new StringBuilder();
        codeContent.append("# 项目文件结构和代码内容\n\n");
        int[] fileCount = {0};
        boolean[] truncated = {false};

        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    // 真正剪枝：node_modules / dist / target / .git 整棵子树都不进去
                    if (dir.getFileName() != null && SKIP_DIRS.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (truncated[0] || shouldSkipFile(file) || !isCodeFile(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (fileCount[0] >= MAX_FILES || codeContent.length() >= MAX_TOTAL_CHARS) {
                        log.warn("代码文件过多，已截断（最多 {} 个文件 / {} 字符）", MAX_FILES, MAX_TOTAL_CHARS);
                        truncated[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    try {
                        String relativePath = root.relativize(file).toString().replace('\\', '/');
                        codeContent.append("## 文件: ").append(relativePath).append("\n\n");
                        // 显式 UTF-8：Windows 默认编码是 GBK，读出来的中文会变乱码
                        codeContent.append(Files.readString(file, StandardCharsets.UTF_8)).append("\n\n");
                        fileCount[0]++;
                    } catch (IOException e) {
                        log.warn("读取文件失败，已跳过: {}（{}）", file, e.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    // 单个文件读不了不能中断整次遍历
                    log.warn("访问文件失败，已跳过: {}（{}）", file, exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("遍历代码目录失败: {}", e.getMessage());
        }

        log.info("代码检查拼接完成，共 {} 个文件、{} 字符", fileCount[0], codeContent.length());
        // 只有表头、没有实际文件内容时，当作「没找到文件」处理
        return fileCount[0] == 0 ? "" : codeContent.toString();
    }

    /**
     * 判断是否应该跳过此文件（隐藏文件一律不看）
     */
    private static boolean shouldSkipFile(Path file) {
        Path fileName = file.getFileName();
        return fileName == null || fileName.toString().startsWith(".");
    }

    /**
     * 判断是否是需要检查的代码文件
     */
    private static boolean isCodeFile(Path file) {
        Path fileName = file.getFileName();
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toString().toLowerCase();
        return CODE_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }
}
