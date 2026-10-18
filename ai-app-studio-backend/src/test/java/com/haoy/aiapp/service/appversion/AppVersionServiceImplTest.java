package com.haoy.aiapp.service.appversion;

import cn.hutool.core.io.FileUtil;
import com.haoy.aiapp.config.AppVersionProperties;
import com.haoy.aiapp.constant.AppConstant;
import com.haoy.aiapp.exception.BusinessException;
import com.haoy.aiapp.mapper.app.AppMapper;
import com.haoy.aiapp.mapper.appversion.AppVersionMapper;
import com.haoy.aiapp.model.entity.App;
import com.haoy.aiapp.model.entity.AppVersion;
import com.haoy.aiapp.model.entity.User;
import com.haoy.aiapp.model.enums.CodeGenTypeEnum;
import com.haoy.aiapp.model.enums.VersionSourceEnum;
import com.mybatisflex.core.logicdelete.LogicDeleteManager;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 版本服务集成测试：真实 MySQL + 真实 Redis + 真实磁盘
 * <p>
 * <b>为什么不 mock 掉这三样：</b>这个功能的价值全在「三者的配合」上 ——
 * 版本号靠 Redis 自增、记录靠 MySQL、文件靠磁盘。任何一层换掉，
 * 测出来的东西和「能不能回滚」已经没关系了。
 * <p>
 * <b>但它不碰 AI、不花钱、不联网</b>，可以在 {@code mvn test} 里跑。
 * 快照根目录在每个用例里重定向到临时目录，生成目录用的是真实路径
 * （因为那是业务代码写死的契约，不该为了测试改），用例结束会清干净。
 *
 * @author <a>程序员haoy</a>
 */
@SpringBootTest
class AppVersionServiceImplTest {

    /**
     * 测试应用的归属人：这个 id 不需要在 user 表里存在，app.userId 上没有外键
     */
    private static final long OWNER_ID = 9_000_001L;

    @TempDir
    Path tempDir;

    @Resource
    private AppVersionService appVersionService;

    @Resource
    private AppVersionProperties properties;

    @Resource
    private AppMapper appMapper;

    @Resource
    private AppVersionMapper appVersionMapper;

    private String originalRootDir;

    private Long appId;

    private Path genDir;

    @BeforeEach
    void setUp() throws IOException {
        originalRootDir = properties.getRootDir();
        // 快照写进临时目录，别污染 E:/ai-app-studio-data/versions
        properties.setRootDir(tempDir.resolve("versions").toString());

        LocalDateTime now = LocalDateTime.now();
        App app = App.builder()
                .appName("版本管理测试应用")
                .initPrompt("生成一个页面")
                .codeGenType(CodeGenTypeEnum.HTML.getValue())
                // priority / editTime / createTime / updateTime 都是 NOT NULL 且没有默认值的列。
                // MyBatis-Flex 的 insert 会把实体里所有字段（含 null）都拼进 SQL，
                // 所以这些字段不显式赋值就插不进去 —— 报错顺序还是一次只提示一个，容易来回折腾。
                .priority(AppConstant.DEFAULT_APP_PRIORITY)
                .editTime(now)
                .userId(OWNER_ID)
                .build();
        app.setCreateTime(now);
        app.setUpdateTime(now);
        appMapper.insert(app);
        appId = app.getId();

        genDir = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, CodeGenTypeEnum.HTML.getValue() + "_" + appId);
        write(genDir.resolve("index.html"), "<html>v1</html>");
    }

    @AfterEach
    void tearDown() {
        properties.setRootDir(originalRootDir);
        if (appId == null) {
            // setUp 就失败了，没有行需要清
            return;
        }
        // 物理删除：这些行留着会污染版本表，而逻辑删只会多出一堆 isDelete=1 的垃圾
        LogicDeleteManager.execWithoutLogicDelete(() -> {
            for (AppVersion version : appVersionMapper.selectListByQuery(
                    com.mybatisflex.core.query.QueryWrapper.create().eq("appId", appId))) {
                appVersionMapper.deleteById(version.getId());
            }
            return appMapper.deleteById(appId);
        });
        FileUtil.del(genDir.toFile());
        FileUtil.del(tempDir.resolve("versions").toFile());
    }

    @Test
    @DisplayName("首次快照：版本号 1、当前版本、文件数与摘要都落库")
    void firstSnapshotIsRecordedAsCurrent() {
        Optional<AppVersion> snapshot = appVersionService.snapshot(
                appId, CodeGenTypeEnum.HTML, VersionSourceEnum.CHAT, "生成一个咖啡店落地页", null);

        assertThat(snapshot).isPresent();
        AppVersion version = snapshot.get();
        assertThat(version.getVersionNo()).isEqualTo(1);
        assertThat(version.getIsCurrent()).isEqualTo(1);
        assertThat(version.getFileCount()).isEqualTo(1);
        assertThat(version.getSource()).isEqualTo(VersionSourceEnum.CHAT.getValue());
        assertThat(version.getContentHash()).hasSize(64);
        // 门面不传 userId，应该用应用归属人兜底，而不是留空
        assertThat(version.getUserId()).isEqualTo(OWNER_ID);
        // 快照目录里应该真的有那份文件
        assertThat(Path.of(properties.getRootDir(), version.getStoragePath(), "index.html")).exists();
    }

    @Test
    @DisplayName("内容没变不记新版本，只把当前标记指回它")
    void unchangedContentDoesNotCreateNewVersion() {
        appVersionService.snapshot(appId, CodeGenTypeEnum.HTML, VersionSourceEnum.CHAT, "第一次", null);
        // 第二次生成的内容和第一次一模一样：这是模型经常干的事
        Optional<AppVersion> second = appVersionService.snapshot(
                appId, CodeGenTypeEnum.HTML, VersionSourceEnum.CHAT, "第二次", null);

        assertThat(appVersionService.listByAppId(appId)).hasSize(1);
        assertThat(second).isPresent();
        assertThat(second.get().getVersionNo()).isEqualTo(1);
        assertThat(appVersionService.getCurrent(appId).getId()).isEqualTo(second.get().getId());
    }

    @Test
    @DisplayName("内容变化 → 新版本号，当前标记跟着移过去")
    void changedContentCreatesNewVersion() {
        appVersionService.snapshot(appId, CodeGenTypeEnum.HTML, VersionSourceEnum.CHAT, "v1", null);
        writeQuietly(genDir.resolve("index.html"), "<html>v2</html>");

        AppVersion v2 = appVersionService.snapshot(
                appId, CodeGenTypeEnum.HTML, VersionSourceEnum.CHAT, "v2", null).orElseThrow();

        assertThat(v2.getVersionNo()).isEqualTo(2);
        List<AppVersion> versions = appVersionService.listByAppId(appId);
        assertThat(versions).hasSize(2);
        // 列表按版本号倒序，最新在前
        assertThat(versions.get(0).getVersionNo()).isEqualTo(2);
        assertThat(versions.get(0).getIsCurrent()).isEqualTo(1);
        assertThat(versions.get(1).getIsCurrent()).isEqualTo(0);
        assertThat(appVersionService.getCurrent(appId).getVersionNo()).isEqualTo(2);
    }

    @Test
    @DisplayName("回滚：文件回到旧内容，当前标记切到旧版本")
    void rollbackRestoresFilesAndMovesCurrent() {
        AppVersion v1 = appVersionService.snapshot(
                appId, CodeGenTypeEnum.HTML, VersionSourceEnum.CHAT, "v1", null).orElseThrow();
        writeQuietly(genDir.resolve("index.html"), "<html>v2</html>");
        appVersionService.snapshot(appId, CodeGenTypeEnum.HTML, VersionSourceEnum.CHAT, "v2", null);

        AppVersion rolledBack = appVersionService.rollback(v1.getId(), owner());

        assertThat(rolledBack.getVersionNo()).isEqualTo(1);
        assertThat(readQuietly(genDir.resolve("index.html"))).isEqualTo("<html>v1</html>");
        assertThat(appVersionService.getCurrent(appId).getVersionNo()).isEqualTo(1);
    }

    @Test
    @DisplayName("当前版本不允许删除，避免用户把自己唯一的版本删掉")
    void currentVersionCannotBeDeleted() {
        AppVersion v1 = appVersionService.snapshot(
                appId, CodeGenTypeEnum.HTML, VersionSourceEnum.CHAT, "v1", null).orElseThrow();

        assertThatThrownBy(() -> appVersionService.deleteVersion(v1.getId(), owner()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("当前版本");
    }

    @Test
    @DisplayName("非本人操作回滚被拒（越权判据在服务层，不靠前端藏按钮）")
    void rollbackRejectsNonOwner() {
        AppVersion v1 = appVersionService.snapshot(
                appId, CodeGenTypeEnum.HTML, VersionSourceEnum.CHAT, "v1", null).orElseThrow();

        User stranger = User.builder().id(OWNER_ID + 1).userRole("user").build();

        assertThatThrownBy(() -> appVersionService.rollback(v1.getId(), stranger))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("应用不存在时静默跳过，别给评估用的伪 appId 留幽灵记录")
    void snapshotSkipsWhenAppDoesNotExist() {
        Optional<AppVersion> ghost = appVersionService.snapshot(
                888_888_888L, CodeGenTypeEnum.HTML, VersionSourceEnum.CHAT, "不存在", null);

        assertThat(ghost).isEmpty();
    }

    @Test
    @DisplayName("删应用时连带清空版本记录与磁盘快照")
    void deleteByAppIdCleansDatabaseAndDisk() {
        appVersionService.snapshot(appId, CodeGenTypeEnum.HTML, VersionSourceEnum.CHAT, "v1", null);
        Path appVersionRoot = Path.of(properties.getRootDir(), String.valueOf(appId));
        assertThat(appVersionRoot).exists();

        appVersionService.deleteByAppId(appId);

        assertThat(appVersionService.listByAppId(appId)).isEmpty();
        assertThat(appVersionRoot).doesNotExist();
    }

    private User owner() {
        return User.builder().id(OWNER_ID).userRole("user").build();
    }

    private void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private void writeQuietly(Path file, String content) {
        try {
            write(file, content);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String readQuietly(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
