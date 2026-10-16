package com.haoy.aiapp.ai.tools;

import cn.hutool.json.JSONObject;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文件读取工具
 * <p>
 * 增量修改的第一步：AI 必须先看清「要改的这个文件现在长什么样」，
 * 才能给出精准的 oldContent → newContent 替换。没有这个工具，
 * AI 只能靠记忆里上一次生成的代码去猜，猜错就是改坏。
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
@Component
public class FileReadTool extends BaseTool {

    /**
     * 单文件读取上限（字节）
     * <p>
     * 工具返回的内容会原样进入 AI 的上下文，一个 {@code package-lock.json} 就上百 KB，
     * 真读进来既烧 token 又挤掉真正有用的代码。超限时直接拒绝并告诉 AI 换个思路
     * ——让它去改而不是读，比读进来一坨用不上的 JSON 更有用。
     */
    private static final long MAX_FILE_SIZE_BYTES = 200 * 1024;

    /**
     * 读取指定文件的内容
     *
     * @param relativeFilePath 文件的相对路径（如 src/pages/Resume.vue）
     * @param appId            当前应用 ID，用于隔离不同应用的文件目录
     * @return 文件内容，或失败提示
     */
    @Tool("读取指定文件的内容")
    public String readFile(
            @P("文件的相对路径")
            String relativeFilePath,
            @ToolMemoryId
            Long appId
    ) {
        return safeExecute(() -> {
            Path path = resolveAppPath(appId, relativeFilePath);
            if (!Files.exists(path)) {
                return "错误：文件不存在 - " + relativeFilePath;
            }
            if (!Files.isRegularFile(path)) {
                return "错误：指定路径不是文件 - " + relativeFilePath;
            }
            long fileSize = Files.size(path);
            if (fileSize > MAX_FILE_SIZE_BYTES) {
                return String.format("错误：文件过大（%d 字节，上限 %d 字节），请改用【文件修改工具】做精确替换 - %s",
                        fileSize, MAX_FILE_SIZE_BYTES, relativeFilePath);
            }
            // 显式指定 UTF-8：项目里的中文文案全靠它，不能依赖平台默认编码
            String content = Files.readString(path, StandardCharsets.UTF_8);
            log.info("成功读取文件: {}，大小: {} 字节", path.toAbsolutePath(), fileSize);
            return content;
        });
    }

    @Override
    public String getToolName() {
        return "readFile";
    }

    @Override
    public String getDisplayName() {
        return "读取文件";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        String relativeFilePath = getStrOrEmpty(arguments, "relativeFilePath");
        return String.format("[工具调用] %s %s", getDisplayName(), relativeFilePath);
    }
}
