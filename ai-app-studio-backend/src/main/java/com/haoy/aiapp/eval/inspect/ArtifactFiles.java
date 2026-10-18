package com.haoy.aiapp.eval.inspect;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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
 * 产物目录的文件读取工具
 * <p>
 * <b>这个类存在的唯一理由是「别扫 node_modules」。</b>
 * 一个生成出来的 Vue 工程里 {@code node_modules} 有两万多个文件，
 * 而 {@code Files.walk(...).filter(...)} 是<b>先遍历、后过滤</b> —— 过滤只让结果变小，
 * 遍历该走的字节一个都没少走。所以这里用 {@code walkFileTree} 在 {@code preVisitDirectory}
 * 里直接 {@code SKIP_SUBTREE}，把整棵子树剪掉。
 * <p>
 * <b>另一个坑：忽略目录只能按「相对产物根目录」的路径判断。</b>
 * 如果拿绝对路径的每一段去比对，那么产物放在 {@code .../target/code_output/html_1} 下时，
 * 祖先目录 {@code target} 会让整个产物被跳过 —— 表现为「目录明明有文件，检查器报空」。
 * 这个错误在写第一版时真实发生过，所以这里从 {@code root.relativize} 之后开始判断。
 *
 * @author <a>程序员haoy</a>
 */
final class ArtifactFiles {

    /**
     * 遍历时整目录跳过的名字
     * <p>
     * 判据：目录里的内容<b>不是 AI 生成的</b>。所以只列依赖目录、构建输出、版本库元数据。
     * 刻意不列 {@code target} —— 那是 Maven 的目录名，它常常出现在产物的<b>祖先路径</b>上，
     * 一旦列进来就会误伤（见类注释）。而 Vue 工程里不会有 {@code target} 目录。
     */
    private static final Set<String> IGNORED_DIRS = Set.of(
            "node_modules", "dist", ".git", ".vite", ".cache", ".nuxt", ".output", "coverage");

    /**
     * 视为「可做文本检查」的扩展名
     * <p>
     * 只列生成链路真的会产出的类型。图片/字体等二进制文件即使读成字符串也会立刻乱码，
     * 正则匹配上去全是误报，不如不读。
     */
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "html", "htm", "css", "js", "mjs", "cjs", "ts", "vue", "json",
            "jsx", "tsx", "md", "txt", "xml", "svg");

    /**
     * 单个文件的读取上限（2MB）
     * <p>
     * 生成出来的代码文件正常都在几十 KB 内。超过这个体积的，要么是打包产物混进来了，
     * 要么是 AI 写飞了 —— 两种情况都不该让检查器去读它。
     * <p>
     * ⚠️ 超过上限时 {@link #read} 只返回前 2MB，内容是<b>被截断的</b>。
     * 所以依赖「完整内容」的检查（标签配平、可见文本量）必须先问 {@link #exceedsReadLimit}，
     * 否则会拿半截文件下结论 —— 一个内嵌 base64 图片的 3MB 页面会被误判成「标签没闭合」。
     */
    static final long MAX_READ_BYTES = 2L * 1024 * 1024;

    private ArtifactFiles() {
    }

    /**
     * 列出目录下所有可做文本检查的文件（已剪掉忽略目录，按路径排序保证结果稳定）
     *
     * @param root 产物根目录
     * @return 文件列表；目录不存在时返回空列表
     */
    static List<Path> textFiles(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        Path absoluteRoot = root.toAbsolutePath().normalize();
        try {
            Files.walkFileTree(absoluteRoot, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(absoluteRoot) && IGNORED_DIRS.contains(dir.getFileName().toString())) {
                        // 剪枝：node_modules 这类目录连进都不进
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && hasTextExtension(file)) {
                        files.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    // 单个文件读不了（占用、权限）不该让整轮检查失败
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("遍历产物目录失败: " + root, e);
        }
        files.sort(Comparator.comparing(Path::toString));
        return List.copyOf(files);
    }

    /**
     * 按扩展名筛选文件（如 {@code html}）
     *
     * @param root 产物根目录
     * @param ext  扩展名，不带点
     */
    static List<Path> filesWithExtension(Path root, String ext) {
        String suffix = "." + ext.toLowerCase();
        return textFiles(root).stream()
                .filter(p -> p.getFileName().toString().toLowerCase().endsWith(suffix))
                .toList();
    }

    /**
     * 安全读取文本：超过 {@link #MAX_READ_BYTES} 时只读前 2MB
     * <p>
     * 为什么不用 {@code Files.readString}：它对超大文件会直接吃掉整个堆，
     * 而检查器是「宁可少查一条，也不能因为一条断言把评估进程搞崩」。
     *
     * @param file 文件
     * @return 文本内容；读取失败返回空串（不抛异常，避免一条坏文件挂掉整轮评估）
     */
    static String read(Path file) {
        try {
            if (Files.size(file) > MAX_READ_BYTES) {
                try (var in = Files.newInputStream(file)) {
                    return new String(in.readNBytes((int) MAX_READ_BYTES), StandardCharsets.UTF_8);
                }
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * 文件是否超出读取上限
     * <p>
     * 给「必须看完整内容才能判断」的检查项用作前置条件：超限就跳过，不要拿半截文件下结论。
     *
     * @param file 文件
     * @return true 表示内容会被截断
     */
    static boolean exceedsReadLimit(Path file) {
        try {
            return Files.exists(file) && Files.size(file) > MAX_READ_BYTES;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 相对路径（报告里用，绝对路径又长又暴露本机目录结构）
     */
    static String relative(Path root, Path file) {
        try {
            return root.toAbsolutePath().normalize()
                    .relativize(file.toAbsolutePath().normalize())
                    .toString()
                    .replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return file.getFileName() == null ? file.toString() : file.getFileName().toString();
        }
    }

    private static boolean hasTextExtension(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return false;
        }
        return TEXT_EXTENSIONS.contains(name.substring(dot + 1));
    }
}
