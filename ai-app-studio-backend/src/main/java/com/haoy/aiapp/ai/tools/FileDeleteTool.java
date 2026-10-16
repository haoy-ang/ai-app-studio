package com.haoy.aiapp.ai.tools;

import cn.hutool.json.JSONObject;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * 文件删除工具
 * <p>
 * 所有工具里破坏性最强的一个：写错了能再写一次，删掉了就得靠 AI 重新生成。
 * 所以它有两道闸门，缺一不可：
 * <ol>
 *   <li><b>重要文件白名单</b>（本类的 {@link #PROTECTED_FILE_NAMES}）——
 *       挡住 {@code package.json}、{@code vite.config.js}、{@code main.js} 这类
 *       「删了项目就跑不起来」的骨架文件。AI 有时为了「清理」会觉得它们碍事。</li>
 *   <li><b>路径越界校验</b>（{@link BaseTool#resolveAppPath}）——
 *       挡住 {@code ../../} 和绝对路径。光有白名单是挡不住这条路的：
 *       {@code C:\Users\xxx\.ssh\id_rsa} 的文件名完全不在白名单里。</li>
 * </ol>
 * 另外这里只允许删单个文件，不支持删目录 —— 目录删除的爆炸半径太大，
 * 而 AI 真要「清空某个文件夹」，逐个删文件的过程反而更可控、更容易被用户发现。
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
@Component
public class FileDeleteTool extends BaseTool {

    /**
     * 不允许删除的重要文件（统一小写，比较时把文件名也转小写）
     */
    private static final Set<String> PROTECTED_FILE_NAMES = Set.of(
            "package.json", "package-lock.json", "yarn.lock", "pnpm-lock.yaml",
            "vite.config.js", "vite.config.ts", "vue.config.js",
            "tsconfig.json", "tsconfig.app.json", "tsconfig.node.json",
            "index.html", "main.js", "main.ts", "app.vue", ".gitignore", "readme.md"
    );

    /**
     * 删除指定路径的文件
     *
     * @param relativeFilePath 文件的相对路径
     * @param appId            当前应用 ID，用于隔离不同应用的文件目录
     * @return 删除结果提示
     */
    @Tool("删除指定路径的文件")
    public String deleteFile(
            @P("文件的相对路径")
            String relativeFilePath,
            @ToolMemoryId
            Long appId
    ) {
        return safeExecute(() -> {
            Path path = resolveAppPath(appId, relativeFilePath);
            if (!Files.exists(path)) {
                // 不存在不算失败，AI 反复删同一个文件时不应该被判成错误
                return "警告：文件不存在，无需删除 - " + relativeFilePath;
            }
            if (!Files.isRegularFile(path)) {
                return "错误：指定路径不是文件，无法删除（本工具不支持删除目录）- " + relativeFilePath;
            }
            String fileName = path.getFileName().toString();
            if (isProtectedFile(fileName)) {
                return "错误：不允许删除重要文件 - " + fileName;
            }
            Files.delete(path);
            log.info("成功删除文件: {}", path.toAbsolutePath());
            return "文件删除成功: " + relativeFilePath;
        });
    }

    /**
     * 判断是否是受保护的重要文件
     */
    private boolean isProtectedFile(String fileName) {
        return PROTECTED_FILE_NAMES.contains(fileName.toLowerCase(Locale.ROOT));
    }

    @Override
    public String getToolName() {
        return "deleteFile";
    }

    @Override
    public String getDisplayName() {
        return "删除文件";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        String relativeFilePath = getStrOrEmpty(arguments, "relativeFilePath");
        return String.format("[工具调用] %s %s", getDisplayName(), relativeFilePath);
    }
}
