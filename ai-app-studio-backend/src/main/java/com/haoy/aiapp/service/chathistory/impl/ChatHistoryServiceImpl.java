package com.haoy.aiapp.service.chathistory.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.haoy.aiapp.constant.UserConstant;
import com.haoy.aiapp.exception.ErrorCode;
import com.haoy.aiapp.exception.ThrowUtils;
import com.haoy.aiapp.mapper.chathistory.ChatHistoryMapper;
import com.haoy.aiapp.model.dto.chathistory.ChatHistoryQueryRequest;
import com.haoy.aiapp.model.entity.App;
import com.haoy.aiapp.model.entity.ChatHistory;
import com.haoy.aiapp.model.entity.User;
import com.haoy.aiapp.model.enums.ChatHistoryMessageTypeEnum;
import com.haoy.aiapp.service.app.AppService;
import com.haoy.aiapp.service.chathistory.ChatHistoryService;
import com.haoy.aiapp.utils.SortFieldValidator;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 对话历史 服务层实现。
 *
 * @author <a>程序员haoy</a>
 */
@Service
@Slf4j
public class ChatHistoryServiceImpl extends ServiceImpl<ChatHistoryMapper, ChatHistory> implements ChatHistoryService {

    /**
     * 允许排序的字段白名单（防止前端传入任意 SQL 片段造成注入）
     */
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "id", "messageType", "appId", "userId", "createTime", "updateTime");

    /**
     * 校验应用归属时需要用到 AppService；
     * 而 AppServiceImpl 又依赖本服务做"保存消息 / 关联删除"，两边互相注入会形成循环依赖，
     * 所以这里加上 @Lazy，让 Spring 注入一个延迟代理，启动时不再互相等待。
     */
    @Resource
    @Lazy
    private AppService appService;

    @Override
    public boolean addChatMessage(Long appId, String message, String messageType, Long userId) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "消息内容不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(messageType), ErrorCode.PARAMS_ERROR, "消息类型不能为空");
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.PARAMS_ERROR, "用户ID不能为空");
        // 校验消息类型是否合法，避免脏数据入库
        ChatHistoryMessageTypeEnum messageTypeEnum = ChatHistoryMessageTypeEnum.getEnumByValue(messageType);
        ThrowUtils.throwIf(messageTypeEnum == null, ErrorCode.PARAMS_ERROR, "不支持的消息类型：" + messageType);
        // 构造入库对象（createTime / updateTime 交给数据库默认值）
        ChatHistory chatHistory = ChatHistory.builder()
                .appId(appId)
                .message(message)
                .messageType(messageType)
                .userId(userId)
                .build();
        return this.save(chatHistory);
    }

    @Override
    public boolean deleteByAppId(Long appId) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不能为空");
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("appId", appId);
        return this.remove(queryWrapper);
    }



    @Override
    public QueryWrapper getQueryWrapper(ChatHistoryQueryRequest chatHistoryQueryRequest) {
        QueryWrapper queryWrapper = QueryWrapper.create();
        if (chatHistoryQueryRequest == null) {
            return queryWrapper;
        }
        Long id = chatHistoryQueryRequest.getId();
        String message = chatHistoryQueryRequest.getMessage();
        String messageType = chatHistoryQueryRequest.getMessageType();
        Long appId = chatHistoryQueryRequest.getAppId();
        Long userId = chatHistoryQueryRequest.getUserId();
        Long lastId = chatHistoryQueryRequest.getLastId();
        LocalDateTime lastCreateTime = chatHistoryQueryRequest.getLastCreateTime();
        String sortField = chatHistoryQueryRequest.getSortField();
        String sortOrder = chatHistoryQueryRequest.getSortOrder();
        // 拼接查询条件
        queryWrapper.eq("id", id)
                .like("message", message)
                .eq("messageType", messageType)
                .eq("appId", appId)
                .eq("userId", userId);
        // 游标查询（优先 id 游标）：
        // id 是雪花号，单调递增且唯一，能精确切分"这一页到哪为止"；
        // 而 createTime 精度只到秒，同一秒里的多条记录会被 lt 条件整批跳过 ——
        // 用户消息和 AI 消息恰恰经常落在同一秒，表现就是翻页时"丢掉了一整轮对话"。
        if (lastId != null) {
            queryWrapper.lt("id", lastId);
        } else if (lastCreateTime != null) {
            // 旧游标兼容分支：只为已经在用的老前端请求保留，新代码不要再走这里
            queryWrapper.lt("createTime", lastCreateTime);
        }
        // 排序：白名单校验通过才用前端指定字段，否则默认按创建时间降序（最新的在最前面）
        String safeSortField = SortFieldValidator.validate(sortField, ALLOWED_SORT_FIELDS);
        if (safeSortField != null) {
            queryWrapper.orderBy(safeSortField, "ascend".equals(sortOrder));
        } else {
            queryWrapper.orderBy("createTime", false);
        }
        return queryWrapper;
    }

    @Override
    public Page<ChatHistory> listAppChatHistoryByPage(Long appId, int pageSize, Long lastId,
                                                      LocalDateTime lastCreateTime,
                                                      User loginUser) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不能为空");
        ThrowUtils.throwIf(pageSize <= 0 || pageSize > 50, ErrorCode.PARAMS_ERROR, "页面大小必须在 1-50 之间");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);
        // 校验应用是否存在
        App app = appService.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        // 权限校验：仅应用创建者和管理员可以查看该应用的对话历史
        boolean isAdmin = UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole());
        boolean isCreator = app.getUserId().equals(loginUser.getId());
        ThrowUtils.throwIf(!isAdmin && !isCreator, ErrorCode.NO_AUTH_ERROR, "无权查看该应用的对话历史");
        // 构建游标查询条件
        ChatHistoryQueryRequest queryRequest = new ChatHistoryQueryRequest();
        queryRequest.setAppId(appId);
        queryRequest.setLastId(lastId);
        queryRequest.setLastCreateTime(lastCreateTime);
        QueryWrapper queryWrapper = this.getQueryWrapper(queryRequest);
        // 游标分页永远只取"第一页"，向前翻页靠游标而不是页码
        return this.page(Page.of(1, pageSize), queryWrapper);
    }

    @Override
    public int loadChatHistoryToMemory(Long appId, MessageWindowChatMemory chatMemory, int maxCount) {
        try {
            // 查询最近 maxCount 条对话记录（跳过最新一条，避免重复加载用户刚发的消息）
            QueryWrapper queryWrapper = QueryWrapper.create()
                    .eq("appId", appId)
                    .orderBy("createTime", false)
                    .limit(maxCount)
                    .offset(1);
            List<ChatHistory> historyList = this.list(queryWrapper);
            if (CollUtil.isEmpty(historyList)) {
                return 0;
            }
            // 反转列表，确保按时间正序（老的在前，新的在后）
            Collections.reverse(historyList);
            // 按时间顺序添加到记忆中
            int loadedCount = 0;
            // 先清理历史缓存，防止重复加载
            chatMemory.clear();
            for (ChatHistory history : historyList) {
                if (ChatHistoryMessageTypeEnum.USER.getValue().equals(history.getMessageType())) {
                    chatMemory.add(UserMessage.from(history.getMessage()));
                    loadedCount++;
                } else if (ChatHistoryMessageTypeEnum.AI.getValue().equals(history.getMessageType())) {
                    chatMemory.add(AiMessage.from(history.getMessage()));
                    loadedCount++;
                }
            }
            log.info("成功为 appId: {} 加载了 {} 条历史对话", appId, loadedCount);
            return loadedCount;
        } catch (Exception e) {
            log.error("加载历史对话失败, appId: {}, error: {}", appId, e.getMessage(), e);
            // 加载失败不影响系统运行，只是没有历史上下文
            return 0;
        }
    }
}
