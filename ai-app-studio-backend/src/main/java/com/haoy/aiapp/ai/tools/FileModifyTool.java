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
import java.nio.file.StandardOpenOption;

/**
 * 文件修改工具
 * <p>
 * 增量修改的核心：只替换文件里的一小段内容，不动其他部分。
 * 相比「让 AI 重写整个文件」，它省 token 也省风险 —— 没被要求改的代码在物理上不可能被改坏。
 * <p>
 * 两个容易踩的点：
 * <ul>
 *   <li>找不到 oldContent 时必须<b>什么都不做</b>并如实告诉 AI。
 *       有些实现会直接写入 newContent，那等于把整个文件删了重写，是最难排查的一类事故。</li>
 *   <li>写回内容必须显式用 UTF-8。默认编码在 Windows 上不一定是 UTF-8，
 *       一个中文标题就可能被写成乱码，而且「看起来只是文案不对」，很难联想到编码。</li>
 * </ul>
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
@Component
public class FileModifyTool extends BaseTool {

    /**
     * 修改指定文件的内容（把旧内容替换成新内容）
     *
     * @param relativeFilePath 文件的相对路径（如 src/pages/Resume.vue）
     * @param oldContent       要替换的旧内容（必须是文件中真实存在的完整片段）
     * @param newContent       替换后的新内容
     * @param appId            当前应用 ID，用于隔离不同应用的文件目录
     * @return 修改结果提示
     */
    @Tool("修改文件内容，用新内容替换文件中已存在的旧内容")
    public String modifyFile(
            @P("文件的相对路径")
            String relativeFilePath,
            @P("要替换的旧内容，必须是文件中真实存在的完整片段")
            String oldContent,
            @P("替换后的新内容")
            String newContent,
            @ToolMemoryId
            Long appId
    ) {
        return safeExecute(() -> {
            Path path = resolveAppPath(appId, relativeFilePath);
            if (!Files.exists(path) || !Files.isRegularFile(path)) {
                return "错误：文件不存在或不是文件 - " + relativeFilePath;
            }
            String originalContent = Files.readString(path, StandardCharsets.UTF_8);
            if (!originalContent.contains(oldContent)) {
                // 不修改、如实反馈，让 AI 先调【文件读取工具】看清楚再改
                return "警告：文件中未找到要替换的内容，文件未修改 - " + relativeFilePath;
            }
            String modifiedContent = originalContent.replace(oldContent, newContent);
            if (originalContent.equals(modifiedContent)) {
                return "信息：替换后文件内容未发生变化 - " + relativeFilePath;
            }
            Files.writeString(path, modifiedContent, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("成功修改文件: {}", path.toAbsolutePath());
            return "文件修改成功: " + relativeFilePath;
        });
    }

    @Override
    public String getToolName() {
        return "modifyFile";
    }

    @Override
    public String getDisplayName() {
        return "修改文件";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        String relativeFilePath = getStrOrEmpty(arguments, "relativeFilePath");
        String oldContent = getStrOrEmpty(arguments, "oldContent");
        String newContent = getStrOrEmpty(arguments, "newContent");
        // 修改和写入的区别就在这：把「替换前 / 替换后」并排展示，用户一眼就能判断改动对不对
        return String.format("""
                        [工具调用] %s %s

                        替换前：
                        ```
                        %s
                        ```

                        替换后：
                        ```
                        %s
                        ```
                        """,
                getDisplayName(), relativeFilePath, oldContent, newContent);
    }
}
