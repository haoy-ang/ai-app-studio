package com.haoy.aiapp.manager;

import com.haoy.aiapp.config.DeployProperties;
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
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 部署目录清理
 * <p>
 * <b>为什么这组测试值得写：</b>这个方法会递归删磁盘目录，路径算错就是删错别人的东西。
 * 三条底线必须钉住 —— 正常的 key 能删干净、非法 key 一律拒绝、任何情况下都碰不到根目录本身。
 *
 * @author <a>程序员haoy</a>
 */
class DeployDirManagerTest {

    @TempDir
    Path tempDir;

    private DeployDirManager manager;

    private Path deployRoot;

    @BeforeEach
    void setUp() throws IOException {
        deployRoot = tempDir.resolve("deploy");
        Files.createDirectories(deployRoot);

        DeployProperties properties = new DeployProperties();
        properties.setRootDir(deployRoot.toString());
        manager = new DeployDirManager();
        ReflectionTestUtils.setField(manager, "deployProperties", properties);
    }

    @Test
    @DisplayName("正常 key：整个目录树连同里面的文件都删掉")
    void deletesWholeDirectoryTree() throws IOException {
        Path appDir = deployRoot.resolve("iMucZn");
        write(appDir.resolve("index.html"), "<html></html>");
        write(appDir.resolve("assets/app.js"), "console.log(1)");

        assertThat(manager.deleteByDeployKey("iMucZn")).isTrue();

        assertThat(appDir).doesNotExist();
        // 根目录本身不能被连带删掉，否则整个站点的其它应用全没了
        assertThat(deployRoot).exists();
    }

    @Test
    @DisplayName("路径穿越：`..` 和分隔符一律拒绝")
    void rejectsPathTraversal() throws IOException {
        Path outside = tempDir.resolve("secret");
        write(outside.resolve("keep.txt"), "do not delete");

        assertThat(manager.deleteByDeployKey("../secret")).isFalse();
        assertThat(manager.deleteByDeployKey("..")).isFalse();
        assertThat(manager.deleteByDeployKey("a/b")).isFalse();
        assertThat(manager.deleteByDeployKey("a\\b")).isFalse();
        assertThat(manager.deleteByDeployKey(".")).isFalse();

        assertThat(outside.resolve("keep.txt")).exists();
        assertThat(deployRoot).exists();
    }

    @Test
    @DisplayName("空 key / 目录不存在：安静返回 false，不抛异常")
    void missingTargetIsNotAnError() {
        assertThat(manager.deleteByDeployKey(null)).isFalse();
        assertThat(manager.deleteByDeployKey("")).isFalse();
        assertThat(manager.deleteByDeployKey("   ")).isFalse();
        assertThat(manager.deleteByDeployKey("noSuchApp")).isFalse();
        assertThatCode(() -> manager.deleteByDeployKey("noSuchApp")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("只删目标目录，兄弟目录不受影响")
    void keepsSiblingDirectories() throws IOException {
        write(deployRoot.resolve("abc123/index.html"), "a");
        write(deployRoot.resolve("xyz789/index.html"), "b");

        manager.deleteByDeployKey("abc123");

        assertThat(deployRoot.resolve("abc123")).doesNotExist();
        assertThat(deployRoot.resolve("xyz789/index.html")).exists();
    }

    private void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
