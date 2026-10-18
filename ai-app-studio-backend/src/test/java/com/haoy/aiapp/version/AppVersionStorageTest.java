package com.haoy.aiapp.version;

import com.haoy.aiapp.config.AppVersionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 版本快照的磁盘存储层 —— 纯 JVM 单测，不碰数据库、不碰 Redis、不花钱
 * <p>
 * <b>为什么这层最需要测：</b>它不报错但会「悄悄做错」。摘要算错 → 每次都判成「内容没变」，
 * 从此一个版本都记不下来；回滚拷错 → 用户点了回滚、页面没变，日志里一行异常都没有。
 * 这两种错在真机上只能靠肉眼发现，所以这里把判据钉死。
 *
 * @author <a>程序员haoy</a>
 */
class AppVersionStorageTest {

    @TempDir
    Path tempDir;

    private AppVersionStorage storage;

    private AppVersionProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AppVersionProperties();
        properties.setRootDir(tempDir.resolve("versions").toString());
        storage = new AppVersionStorage();
        // storage 里的 properties 是 @Resource 注入的，单测里没有容器，手动塞进去
        ReflectionTestUtils.setField(storage, "properties", properties);
    }

    @Test
    @DisplayName("摘要跳过 node_modules / dist / 构建日志，只算真正的源码文件")
    void describeIgnoresDerivedArtifacts() throws IOException {
        Path source = tempDir.resolve("src");
        write(source.resolve("index.html"), "<html></html>");
        write(source.resolve("package.json"), "{}");
        // 下面这些都不该计入：一个能由 package.json 重建，一个是构建输出，一个是运行日志
        write(source.resolve("node_modules/vue/index.js"), "module.exports = {}");
        write(source.resolve("dist/assets/index.js"), "console.log(1)");
        write(source.resolve("build.log"), "npm build finished");

        AppVersionStorage.Manifest manifest = storage.describe(source);

        assertThat(manifest).isNotNull();
        assertThat(manifest.fileCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("内容相同摘要相同；只改文件名摘要也必须变")
    void describeIsStableAndPathSensitive() throws IOException {
        Path a = tempDir.resolve("a");
        write(a.resolve("index.html"), "<html>hi</html>");
        write(a.resolve("style.css"), "body{margin:0}");

        Path b = tempDir.resolve("b");
        write(b.resolve("index.html"), "<html>hi</html>");
        write(b.resolve("style.css"), "body{margin:0}");

        // 同一个内容放进不同目录：摘要必须一致，否则「重新生成了一遍但内容没变」判不出来
        assertThat(storage.describe(a).contentHash()).isEqualTo(storage.describe(b).contentHash());

        // 只把 style.css 改名成 theme.css，内容一字不改
        Files.delete(b.resolve("style.css"));
        write(b.resolve("theme.css"), "body{margin:0}");

        // 摘要里拼了相对路径，所以这种「只改名」必须被判成变化 ——
        // 否则页面会引用一个不存在的文件，而版本系统认为「和上一版一样」
        assertThat(storage.describe(b).contentHash()).isNotEqualTo(storage.describe(a).contentHash());
    }

    @Test
    @DisplayName("回滚：源码回到快照状态，快照里没有的旧文件被删掉，node_modules 保留")
    void restoreRealignsSourceButKeepsDerivedDirs() throws IOException {
        Path source = tempDir.resolve("gen");
        write(source.resolve("index.html"), "<html>v1</html>");
        write(source.resolve("app.js"), "console.log('v1')");

        assertThat(storage.copy(source, 42L, 1)).isTrue();

        // 模拟「第 2 版」：改了内容、删了一个文件、多了个新文件，还装好了依赖
        write(source.resolve("index.html"), "<html>v2</html>");
        Files.delete(source.resolve("app.js"));
        write(source.resolve("new-feature.js"), "console.log('v2')");
        write(source.resolve("node_modules/vue/index.js"), "module.exports = {}");

        assertThat(storage.restore(42L, 1, source)).isTrue();

        // 回到 v1 的内容
        assertThat(read(source.resolve("index.html"))).isEqualTo("<html>v1</html>");
        // v1 有、v2 被删掉的文件要回来
        assertThat(read(source.resolve("app.js"))).isEqualTo("console.log('v1')");
        // v2 多出来的文件必须消失，否则页面上会残留一个「上一版的功能」
        assertThat(source.resolve("new-feature.js")).doesNotExist();
        // 依赖目录是派生资产，回滚不该把它删掉（删了下次部署要重装几分钟依赖）
        assertThat(source.resolve("node_modules/vue/index.js")).exists();
    }

    @Test
    @DisplayName("空目录、不存在的目录都返回 null，不是抛异常")
    void describeReturnsNullForUnusableSource() throws IOException {
        Path empty = tempDir.resolve("empty");
        Files.createDirectories(empty);

        assertThat(storage.describe(empty)).isNull();
        assertThat(storage.describe(tempDir.resolve("missing"))).isNull();
        assertThat(storage.describe(null)).isNull();
    }

    @Test
    @DisplayName("超过体积上限直接放弃，宁可少一个版本也不写爆磁盘")
    void describeRejectsOversizeArtifact() throws IOException {
        Path source = tempDir.resolve("big");
        write(source.resolve("index.html"), "x".repeat(2048));

        properties.setMaxSizeBytes(1024);

        assertThat(storage.describe(source)).isNull();
    }

    @Test
    @DisplayName("快照目录布局是 {rootDir}/{appId}/{versionNo}，存库的相对路径与之一致")
    void storagePathMatchesDirectoryLayout() {
        assertThat(storage.storagePath(42L, 3)).isEqualTo("42/3");
        assertThat(storage.versionDir(42L, 3))
                .isEqualTo(Path.of(properties.getRootDir()).resolve("42").resolve("3"));
    }

    private void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
