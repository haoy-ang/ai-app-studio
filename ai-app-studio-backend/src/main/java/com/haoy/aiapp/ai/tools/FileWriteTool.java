package com.haoy.aiapp.ai.tools;

import cn.hutool.core.io.FileUtil;
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
 * 文件写入工具
 * <p>
 * 支持 AI 通过工具调用的方式写入文件到指定路径。
 * AI 传入相对路径 + 文件内容，工具自动基于 appId 定位项目目录并写入。
 * 返回的是相对路径（不暴露服务器绝对路径给前端）。
 * <p>
 * 本类继承 {@link BaseTool}，路径解析、越界校验、异常兜底都在基类里，
 * 五个文件工具共用同一套规则 —— 新增工具时不用再复制一遍。
 * <p>
 * ⚠️ appId 参数必须用 {@link ToolMemoryId} 而不是 {@code dev.langchain4j.service.MemoryId}：
 * 这两个注解同名不同包、用途完全不同 ——
 * {@code service.MemoryId} 只用于 AiService **接口方法**的参数；
 * {@code agent.tool.ToolMemoryId} 才是「工具方法里由框架自动注入」的标记。
 * 如果工具参数上标错（或干脆不标），LangChain4j 会把它当成一个**需要 AI 自己填**的普通参数，
 * AI 只能编一个数字出来，文件就会被写到 {@code vue_project_{乱编的appId}} 目录，
 * 而预览和部署都是按真实 appId 找目录，结果永远 404。
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
@Component
public class FileWriteTool extends BaseTool {

    /**
     * 写入文件到指定路径
     *
     * @param relativeFilePath 文件的相对路径（如 src/components/Hello.vue）
     * @param content          要写入文件的完整内容
     * @param appId            当前应用 ID，用于隔离不同应用的文件目录
     * @return 写入成功/失败的提示信息
     */
    @Tool("写入文件到指定路径")
    public String writeFile(
            @P("文件的相对路径")
            String relativeFilePath,
            @P("要写入文件的内容")
            String content,
            @ToolMemoryId
            Long appId
    ) {
        return safeExecute(() -> {
            // 路径解析与越界校验统一走基类，五个文件工具用的是同一套规则
            Path path = resolveAppPath(appId, relativeFilePath);
            // ① 幂等刹车：内容一模一样的重复写入直接跳过。
            // Vue 工具调用生成时模型偶尔会陷进「一轮一轮重写同一批文件」的死循环，
            // 这里给一个确定性的刹车：内容没变就不落盘，并把「没有变化」明确回给模型，
            // 让它有机会意识到项目已经写完、应该结束回复（实测比只依赖提示词可靠得多）。
            if (Files.exists(path)) {
                String oldContent = Files.readString(path, StandardCharsets.UTF_8);
                if (oldContent.equals(content)) {
                    log.info("跳过重复写入（内容未变化）: {}", path.toAbsolutePath());
                    return "文件内容与现有内容完全相同，已跳过写入: " + relativeFilePath
                            + "。如果本次计划中的所有文件都已创建完毕，请立即结束回复，不要再调用任何工具。";
                }
            }
            // 创建父目录（如果不存在）
            Path parentDir = path.getParent();
            if (parentDir != null) {
                Files.createDirectories(parentDir);
            }
            // 显式指定 UTF-8：默认编码在 Windows 上不保证是 UTF-8，中文会变乱码
            Files.write(path, content.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            log.info("成功写入文件: {}", path.toAbsolutePath());
            // 注意要返回相对路径，不能让 AI 把服务器绝对路径暴露给用户
            return "文件写入成功: " + relativeFilePath;
        });
    }

    @Override
    public String getToolName() {
        return "writeFile";
    }

    @Override
    public String getDisplayName() {
        return "写入文件";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        String relativeFilePath = getStrOrEmpty(arguments, "relativeFilePath");
        String content = getStrOrEmpty(arguments, "content");
        // 带上语言标注，前端按 Markdown 渲染时才能正确高亮代码
        String suffix = FileUtil.getSuffix(relativeFilePath);
        return String.format("""
                        [工具调用] %s %s
                        ```%s
                        %s
                        ```
                        """,
                getDisplayName(), relativeFilePath, suffix, content);
    }
}
