package com.haoy.aiapp.service.user;

import com.haoy.aiapp.constant.AppConstant;
import com.haoy.aiapp.exception.BusinessException;
import com.haoy.aiapp.mapper.app.AppMapper;
import com.haoy.aiapp.mapper.chathistory.ChatHistoryMapper;
import com.haoy.aiapp.mapper.user.UserMapper;
import com.haoy.aiapp.model.entity.App;
import com.haoy.aiapp.model.entity.ChatHistory;
import com.haoy.aiapp.model.entity.User;
import com.haoy.aiapp.model.enums.ChatHistoryMessageTypeEnum;
import com.haoy.aiapp.model.enums.CodeGenTypeEnum;
import com.haoy.aiapp.model.enums.UserRoleEnum;
import com.mybatisflex.core.logicdelete.LogicDeleteManager;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 用户删除（级联清理）集成测试：真实 MySQL
 * <p>
 * <b>为什么必须是真库：</b>这里要验的正是「跨表 + 逻辑删除 + 唯一索引」三者的相互作用 ——
 * 逻辑删除只把 isDelete 置 1，行还占着 uk_userAccount；用 mock 测不出这件事。
 * <p>
 * 不碰 AI、不联网，可以在 {@code mvn test} 里跑。
 *
 * @author <a>程序员haoy</a>
 */
@SpringBootTest
class UserServiceImplTest {

    /**
     * 操作人（管理员）：只需要是个不同的 id，不需要真的存在于 user 表
     */
    private static final long OPERATOR_ID = 9_100_001L;

    @Resource
    private UserService userService;

    @Resource
    private UserMapper userMapper;

    @Resource
    private AppMapper appMapper;

    @Resource
    private ChatHistoryMapper chatHistoryMapper;

    /**
     * 用例里创建的数据，结束时要物理删除（逻辑删除会把垃圾留在表里，还会占着唯一索引）
     */
    private final List<Long> createdUserIds = new ArrayList<>();

    private final List<Long> createdAppIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        if (createdUserIds.isEmpty() && createdAppIds.isEmpty()) {
            return;
        }
        LogicDeleteManager.execWithoutLogicDelete(() -> {
            for (Long appId : createdAppIds) {
                chatHistoryMapper.deleteByQuery(QueryWrapper.create().eq("appId", appId));
                appMapper.deleteById(appId);
            }
            for (Long userId : createdUserIds) {
                chatHistoryMapper.deleteByQuery(QueryWrapper.create().eq("userId", userId));
                userMapper.deleteById(userId);
            }
            return null;
        });
        createdUserIds.clear();
        createdAppIds.clear();
    }

    @Test
    @DisplayName("删用户会级联删掉他的应用与对话历史")
    void removeUserCascadesOwnedApps() {
        Long ownerId = createUser("cascade");
        Long appId = createApp(ownerId);
        LocalDateTime now = LocalDateTime.now();
        ChatHistory history = ChatHistory.builder()
                .appId(appId)
                .userId(ownerId)
                .message("帮我做个页面")
                .messageType(ChatHistoryMessageTypeEnum.USER.getValue())
                .build();
        history.setCreateTime(now);
        history.setUpdateTime(now);
        chatHistoryMapper.insert(history);

        boolean result = userService.removeUser(ownerId, OPERATOR_ID);

        assertThat(result).isTrue();
        assertThat(userService.getById(ownerId)).as("用户应已被删除").isNull();
        assertThat(appMapper.selectOneById(appId)).as("名下的应用应一并删除").isNull();
        assertThat(chatHistoryMapper.selectListByQuery(QueryWrapper.create().eq("appId", appId)))
                .as("对话历史应一并删除").isEmpty();
    }

    @Test
    @DisplayName("不能删除当前登录的账号")
    void removeUserRejectsSelf() {
        Long adminId = createUser("self");

        assertThatThrownBy(() -> userService.removeUser(adminId, adminId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能删除当前登录的账号");
        assertThat(userService.getById(adminId)).as("自己不能被删掉").isNotNull();
    }

    @Test
    @DisplayName("删除不存在的用户返回明确错误，而不是静默成功")
    void removeUserRejectsMissingUser() {
        assertThatThrownBy(() -> userService.removeUser(9_999_999_999L, OPERATOR_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用户不存在");
    }

    @Test
    @DisplayName("删除后账号被释放，可以重新注册同名账号")
    void removeUserReleasesAccount() {
        Long userId = createUser("reuse");
        User before = userService.getById(userId);
        String account = before.getUserAccount();

        userService.removeUser(userId, OPERATOR_ID);

        // 逻辑删除后行还在表里，若账号没被释放，这里会撞 uk_userAccount 抛「注册失败,数据库错误」
        Long newUserId = userService.userRegister(account, "password123", "password123");
        createdUserIds.add(newUserId);
        assertThat(newUserId).isNotNull();
        assertThat(userService.getById(newUserId).getUserAccount()).isEqualTo(account);
    }

    /**
     * 造一个可删除的普通用户
     */
    private Long createUser(String prefix) {
        LocalDateTime now = LocalDateTime.now();
        User user = User.builder()
                // 账号要唯一：并发/重跑时加时间戳后缀，避免撞 uk_userAccount
                .userAccount(prefix + "_" + System.nanoTime())
                .userPassword(userService.getEncryptPassword("password123"))
                .userName("测试" + prefix)
                .userRole(UserRoleEnum.USER.getValue())
                .editTime(now)
                .build();
        user.setCreateTime(now);
        user.setUpdateTime(now);
        userMapper.insert(user);
        createdUserIds.add(user.getId());
        return user.getId();
    }

    /**
     * 造一个归属该用户的应用
     */
    private Long createApp(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        App app = App.builder()
                .appName("级联删除测试应用")
                .initPrompt("生成一个页面")
                .codeGenType(CodeGenTypeEnum.HTML.getValue())
                // priority / editTime / createTime / updateTime 都是 NOT NULL 且无默认值
                .priority(AppConstant.DEFAULT_APP_PRIORITY)
                .editTime(now)
                .userId(userId)
                .build();
        app.setCreateTime(now);
        app.setUpdateTime(now);
        appMapper.insert(app);
        createdAppIds.add(app.getId());
        return app.getId();
    }
}
