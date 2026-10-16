package com.haoy.aiapp.ai.tools;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 文件目录读取工具
 * <p>
 * AI 修改代码前的「侦察兵」：先看清项目里有哪些文件，再去读具体文件。
 * 不用它，AI 只能凭想象猜文件名，改出来的路径很可能根本不存在。
 * <p>
 * 相比「只输出文件名＋缩进」的实现，这里有两处改进：
 * <ol>
 *   <li><b>输出相对项目根目录的路径，而不是文件名。</b>
 *       只输出文件名时，AI 拿到「Resume.vue」之后还得自己拼出
 *       {@code src/pages/Resume.vue} 才能去读，拼错就是一次无效调用。
 *       直接给相对路径，输出可以直接当作其它工具的 relativeFilePath 参数使用。</li>
 *   <li><b>忽略目录是真的剪掉，不是事后过滤。</b>见 {@link #collectFiles} 的说明。</li>
 * </ol>
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
@Component
public class FileDirReadTool extends BaseTool {

    /**
     * 需要忽略的目录 / 文件名
     * <p>
     * 这些内容要么和「读现有代码」无关，要么体量大到会把上下文塞满。
     * 尤其 {@code node_modules}：一个 Vue 项目装完依赖有几万个文件，
     * 漏了这一个忽略项，这个工具会从「秒回」变成「把 AI 的上下文烧光」。
     */
    private static final Set<String> IGNORED_NAMES = Set.of(
            "node_modules", ".git", "dist", "build", ".DS_Store",
            ".env", "target", ".mvn", ".idea", ".vscode", "coverage"
    );

    /**
     * 需要忽略的文件扩展名
     */
    private static final Set<String> IGNORED_EXTENSIONS = Set.of(
            ".log", ".tmp", ".cache", ".lock"
    );

    /**
     * 读取目录结构
     *
     * @param relativeDirPath 目录的相对路径，为空则读取整个项目结构
     * @param appId           当前应用 ID，用于隔离不同应用的文件目录
     * @return 目录结构文本，或失败提示
     */
    @Tool("读取项目目录结构，返回所有文件和子目录的相对路径")
    public String readDir(
            @P("目录的相对路径，为空则读取整个项目结构")
            String relativeDirPath,
            @ToolMemoryId
            Long appId
    ) {
        return safeExecute(() -> {
            Path projectRoot = getProjectRoot(appId);
            // 空路径的语义是「整个项目」，所以这里走的是 resolveAppPath 的兜底分支
            Path targetDir = resolveAppPath(appId, relativeDirPath);
            if (!Files.exists(targetDir) || !Files.isDirectory(targetDir)) {
                return "错误：目录不存在或不是目录 - " + relativeDirPath;
            }
            List<Path> allFiles = collectFiles(targetDir);
            StringBuilder structure = new StringBuilder("项目目录结构（相对项目根目录）:\n");
            allFiles.stream()
                    // 先按层级、再按路径排序，保证输出稳定（否则每次顺序不同，AI 容易误判文件被改动）
                    .sorted(Comparator
                            .comparingInt((Path file) -> getRelativeDepth(projectRoot, file))
                            .thenComparing(file -> toRelativePath(projectRoot, file)))
                    .forEach(file -> structure
                            .append("  ".repeat(getRelativeDepth(projectRoot, file)))
                            .append(toRelativePath(projectRoot, file))
                            .append("\n"));
            return structure.toString();
        });
    }

    /**
     * 递归收集目录下的所有文件，遇到忽略的目录整棵剪掉
     * <p>
     * ⚠️ 这里<b>没有</b>用 Hutool 的 {@code FileUtil.loopFiles(dir, fileFilter)}。
     * 那个重载的 filter 只作用于文件：Hutool 的实现遇到目录时无条件递归，
     * 根本不会拿 filter 去判断目录 —— 也就是说「忽略 node_modules」的名单形同虚设，
     * node_modules 里几万个文件照样会被逐个列出来（{@code FileToolTest#testReadDirStructure}
     * 会断言输出中不含 node_modules）。
     * <p>
     * 真正的剪枝要在「进入目录之前」就返回 {@code SKIP_SUBTREE}，
     * 所以这里用 {@link Files#walkFileTree} 自己写：
     * preVisitDirectory 负责剪枝、visitFile 负责收文件，两者职责分明。
     *
     * @param targetDir 起始目录
     * @return 收集到的文件列表
     * @throws IOException 遍历出错
     */
    private List<Path> collectFiles(Path targetDir) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(targetDir, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                // 起始目录本身不能剪，否则 AI 主动指定 node_modules 时什么都读不到
                if (!dir.equals(targetDir) && shouldIgnore(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && !shouldIgnore(file.getFileName().toString())) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    /**
     * 计算文件相对项目根目录的层级（根目录下的文件为 0）
     */
    private int getRelativeDepth(Path projectRoot, Path file) {
        return projectRoot.relativize(file).getNameCount() - 1;
    }

    /**
     * 拼接相对项目根目录的路径，并把 Windows 的反斜杠统一成正斜杠
     * <p>
     * 统一成正斜杠的原因：这个字符串会被 AI 原样用作其它工具的相对路径参数，
     * 而 AI 输出 JSON 时 {@code \} 是转义字符，反斜杠路径极易被转义坏。
     */
    private String toRelativePath(Path projectRoot, Path file) {
        return projectRoot.relativize(file).toString().replace('\\', '/');
    }

    /**
     * 判断是否应忽略该文件或目录
     */
    private boolean shouldIgnore(String fileName) {
        if (IGNORED_NAMES.contains(fileName)) {
            return true;
        }
        return IGNORED_EXTENSIONS.stream().anyMatch(
                extension -> fileName.toLowerCase().endsWith(extension));
    }

    @Override
    public String getToolName() {
        return "readDir";
    }

    @Override
    public String getDisplayName() {
        return "读取目录";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        String relativeDirPath = getStrOrEmpty(arguments, "relativeDirPath");
        if (StrUtil.isBlank(relativeDirPath)) {
            relativeDirPath = "根目录";
        }
        return String.format("[工具调用] %s %s", getDisplayName(), relativeDirPath);
    }
}
