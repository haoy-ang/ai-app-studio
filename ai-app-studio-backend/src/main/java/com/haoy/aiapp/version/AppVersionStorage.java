package com.haoy.aiapp.version;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.haoy.aiapp.config.AppVersionProperties;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * 版本快照的存储层 —— 只管「磁盘上怎么放、怎么拷、怎么算摘要」，不碰数据库
 * <p>
 * 目录布局：{@code {rootDir}/{appId}/{versionNo}/...}，与生成目录
 * {@code {CODE_OUTPUT_ROOT_DIR}/{codeGenType}_{appId}} <b>完全同构</b>，
 * 所以「回滚」就是把快照目录整棵拷回生成目录，不需要任何格式转换。
 * <p>
 * <b>三个必须做对的细节：</b>
 * <ol>
 *   <li><b>排除 node_modules / dist</b>：Vue 工程的依赖目录动辄几万文件几百 MB，
 *       把它们算进快照，十个版本就能撑爆磁盘，而且它们本来就能由 package.json 重建；</li>
 *   <li><b>摘要要先排序再算</b>：{@code walkFileTree} 的遍历顺序不保证稳定，
 *       不排序的话同一份产物两次算出的 hash 可能不同，「内容没变」就判不出来；</li>
 *   <li><b>体积先算后拷</b>：先统计总字节数，超过上限直接放弃，不要拷到一半再删。</li>
 * </ol>
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
@Component
public class AppVersionStorage {

    /**
     * 快照时整棵跳过的目录名
     */
    private static final Set<String> IGNORED_DIRS = Set.of(
            "node_modules", "dist", ".git", ".idea", ".vite", "target", "__pycache__");

    /**
     * 快照时跳过的文件名（构建日志属于运行痕迹，不是产物）
     */
    private static final Set<String> IGNORED_FILES = Set.of("build.log", "npm-build.log", ".DS_Store");

    @Resource
    private AppVersionProperties properties;

    /**
     * 产物清单：不写盘，只描述「这次产物长什么样」
     * <p>
     * 拆出来是为了<b>先判重再拷贝</b>：如果内容和上一版一样，根本不需要往磁盘上再放一份。
     *
     * @param contentHash sha256 内容摘要
     * @param fileCount   文件数
     * @param sizeBytes   总体积（字节）
     */
    public record Manifest(String contentHash, int fileCount, long sizeBytes) {
    }

    /**
     * 描述某个产物目录（算摘要 + 统计文件数与体积），<b>不写任何文件</b>
     *
     * @param sourceDir 产物目录
     * @return 清单；目录不存在/为空/超体积上限时返回 {@code null}
     */
    public Manifest describe(Path sourceDir) {
        if (sourceDir == null || !Files.isDirectory(sourceDir)) {
            log.warn("快照跳过：产物目录不存在，dir: {}", sourceDir);
            return null;
        }
        List<Path> files = collectFiles(sourceDir);
        if (files.isEmpty()) {
            log.warn("快照跳过：产物目录里没有可归档文件，dir: {}", sourceDir);
            return null;
        }
        long totalSize = files.stream().mapToLong(this::safeSize).sum();
        if (totalSize > properties.getMaxSizeBytes()) {
            log.warn("快照跳过：产物体积 {} 字节超过上限 {} 字节，dir: {}",
                    totalSize, properties.getMaxSizeBytes(), sourceDir);
            return null;
        }
        MessageDigest digest = newDigest();
        try {
            for (Path file : files) {
                Path relative = sourceDir.relativize(file);
                // 摘要里拼上相对路径：只比内容不比路径的话，
                // 「把 a.css 改名成 b.css」会被判成「没有变化」
                digest.update(normalize(relative).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                updateDigestWithFile(digest, file);
            }
        } catch (IOException e) {
            log.error("计算产物摘要失败，dir: {}", sourceDir, e);
            return null;
        }
        return new Manifest(HexFormat.of().formatHex(digest.digest()), files.size(), totalSize);
    }

    /**
     * 把产物目录拷进版本目录
     *
     * @param sourceDir 产物目录
     * @param appId     应用 id
     * @param versionNo 版本号
     * @return 是否成功（失败会清掉半个目录）
     */
    public boolean copy(Path sourceDir, long appId, int versionNo) {
        Path targetDir = versionDir(appId, versionNo);
        try {
            // 先清空：版本号是新的，理论上目录不存在；留着这段是为了「删记录但没删干净」的残留
            FileUtil.clean(targetDir.toFile());
            FileUtil.mkdir(targetDir.toFile());
            for (Path file : collectFiles(sourceDir)) {
                Path target = targetDir.resolve(normalize(sourceDir.relativize(file)));
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception e) {
            // 失败要收拾干净：半个快照目录留在磁盘上，将来会被误当成「可回滚的版本」
            log.error("快照写入失败，已清掉目录，appId: {}，versionNo: {}", appId, versionNo, e);
            FileUtil.del(targetDir.toFile());
            return false;
        }
    }

    /**
     * 快照目录（相对版本根目录）的字符串形式，存库用
     *
     * @param appId     应用 id
     * @param versionNo 版本号
     * @return 如 {@code 1234/3}
     */
    public String storagePath(long appId, int versionNo) {
        return relativePath(appId, versionNo);
    }

    /**
     * 把某个版本的内容写回生成目录（回滚的最后一步）
     * <p>
     * <b>为什么不是「先 clean 再整体拷」：</b>
     * 那样会把 {@code node_modules}、{@code dist} 一起删掉。Vue 工程的 node_modules
     * 动辄几万文件几百 MB，删了意味着用户下一次部署要重新 {@code npm install}（几分钟）；
     * 而这两个目录本来就不在快照里 —— 它们是「由源码派生出来的」，删了纯属自损。
     * <p>
     * 所以这里做的是<b>精确对齐</b>：快照里有的文件覆盖写回，快照里没有的源码文件删掉
     * （否则会出现「回滚了但上一版多出来的文件还在」这种鬼现象），
     * 被排除的派生目录整个原样保留。
     *
     * @param appId     应用 id
     * @param versionNo 版本号
     * @param targetDir 生成目录
     * @return 是否成功
     */
    public boolean restore(long appId, int versionNo, Path targetDir) {
        Path sourceDir = versionDir(appId, versionNo);
        if (!Files.isDirectory(sourceDir)) {
            log.error("回滚失败：快照目录不存在，appId: {}，versionNo: {}，dir: {}", appId, versionNo, sourceDir);
            return false;
        }
        try {
            List<Path> snapshotFiles = collectFiles(sourceDir);
            // 快照里的相对路径集合（正斜杠形式，直接可比）
            Set<String> keep = new HashSet<>();
            for (Path file : snapshotFiles) {
                keep.add(normalize(sourceDir.relativize(file)));
            }
            FileUtil.mkdir(targetDir.toFile());
            // 1. 删掉目标目录里「不在快照中」的源码文件
            if (Files.isDirectory(targetDir)) {
                for (Path stale : collectFiles(targetDir)) {
                    if (!keep.contains(normalize(targetDir.relativize(stale)))) {
                        Files.deleteIfExists(stale);
                    }
                }
            }
            // 2. 把快照文件覆盖回去
            for (Path file : snapshotFiles) {
                Path target = targetDir.resolve(normalize(sourceDir.relativize(file)));
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception e) {
            log.error("回滚写盘失败，appId: {}，versionNo: {}", appId, versionNo, e);
            return false;
        }
    }

    /**
     * 删除某个版本的快照目录（幂等）
     *
     * @param appId     应用 id
     * @param versionNo 版本号
     */
    public void delete(long appId, int versionNo) {
        try {
            FileUtil.del(versionDir(appId, versionNo).toFile());
        } catch (Exception e) {
            log.warn("删除快照目录失败，appId: {}，versionNo: {}", appId, versionNo, e);
        }
    }

    /**
     * 删除某个应用的全部快照目录（应用被删除时调用）
     *
     * @param appId 应用 id
     */
    public void deleteAll(long appId) {
        try {
            FileUtil.del(appDir(appId).toFile());
        } catch (Exception e) {
            log.warn("删除应用快照根目录失败，appId: {}", appId, e);
        }
    }

    /**
     * 快照目录：{@code {rootDir}/{appId}/{versionNo}}
     */
    public Path versionDir(long appId, int versionNo) {
        return appDir(appId).resolve(String.valueOf(versionNo));
    }

    private Path appDir(long appId) {
        return Paths.get(properties.getRootDir()).resolve(String.valueOf(appId));
    }

    private String relativePath(long appId, int versionNo) {
        return appId + "/" + versionNo;
    }

    /**
     * 列出需要归档的文件（已排除忽略目录/忽略文件），保持稳定顺序
     */
    List<Path> collectFiles(Path sourceDir) {
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(sourceDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(sourceDir) && IGNORED_DIRS.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && !IGNORED_FILES.contains(file.getFileName().toString())) {
                        files.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    // 单个文件读不到不该让整次快照失败（Windows 上偶发文件锁）
                    log.warn("跳过无法访问的文件: {} ({})", file, exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("遍历产物目录失败: {}", sourceDir, e);
            return List.of();
        }
        files.sort(Comparator.comparing(p -> normalize(sourceDir.relativize(p))));
        return files;
    }

    private void updateDigestWithFile(MessageDigest digest, Path file) throws IOException {
        byte[] buffer = new byte[8192];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
    }

    private long safeSize(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * 统一成正斜杠：Windows 上 {@code relativize} 给的是反斜杠，
     * 直接拿去拼摘要，同一份产物在 Linux 和 Windows 上会算出不同的 hash
     */
    private String normalize(Path relative) {
        return StrUtil.replace(relative.toString(), "\\", "/");
    }

    /**
     * 兜底：{@link MessageDigest} 理论上不会缺 SHA-256，真缺了也不该把生成流程带崩
     */
    static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }
}
