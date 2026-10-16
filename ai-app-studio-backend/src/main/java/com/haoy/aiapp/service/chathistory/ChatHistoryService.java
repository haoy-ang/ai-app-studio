package com.haoy.aiapp.service.chathistory;

import com.haoy.aiapp.model.dto.chathistory.ChatHistoryQueryRequest;
import com.haoy.aiapp.model.entity.ChatHistory;
import com.haoy.aiapp.model.entity.User;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.service.IService;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;

import java.time.LocalDateTime;

/**
 * 对话历史 服务层。
 *
 * @author <a>程序员haoy</a>
 */
public interface ChatHistoryService extends IService<ChatHistory> {

    /**
     * 新增一条对话消息（用户消息 / AI 消息 统一入口）
     *
     * @param appId       应用 id
     * @param message     消息内容
     * @param messageType 消息类型（user/ai）
     * @param userId      发送消息的用户 id
     * @return 是否保存成功
     */
    boolean addChatMessage(Long appId, String message, String messageType, Long userId);

    /**
     * 根据应用 id 删除该应用下的所有对话历史（删除应用时关联删除）
     *
     * @param appId 应用 id
     * @return 是否删除成功
     */
    boolean deleteByAppId(Long appId);

    /**
     * 根据查询条件构造数据查询参数
     *
     * @param chatHistoryQueryRequest 对话历史查询请求
     * @return 查询参数
     */
    QueryWrapper getQueryWrapper(ChatHistoryQueryRequest chatHistoryQueryRequest);

    /**
     * 游标分页查询某个应用的对话历史（仅应用创建者或管理员可见）
     * <p>
     * 每次查询"游标之前"的最新 pageSize 条记录，按创建时间降序返回；
     * 前端取最后一条记录的 id 作为下一次查询的游标，即可不断向前加载更早的历史。
     *
     * @param appId          应用 id
     * @param pageSize       每页条数（1-50）
     * @param lastId         游标：上一页最后一条记录的 id（<b>推荐</b>，精确且不受同秒影响）
     * @param lastCreateTime 旧游标：上一页最后一条记录的创建时间（仅兼容老客户端，lastId 非空时忽略）
     * @param loginUser      登录用户
     * @return 对话历史分页
     */
    Page<ChatHistory> listAppChatHistoryByPage(Long appId, int pageSize, Long lastId,
                                              LocalDateTime lastCreateTime, User loginUser);

    /**
     * 从数据库加载指定应用的历史对话到 LangChain4j 聊天记忆中
     * <p>
     * 查询最近 maxCount 条对话（跳过最新一条，避免重复加载），
     * 按时间正序写入 chatMemory
     *
     * @param appId      应用 ID
     * @param chatMemory LangChain4j 的聊天记忆窗口
     * @param maxCount   最大加载条数
     * @return 实际加载的消息条数
     */
    int loadChatHistoryToMemory(Long appId, MessageWindowChatMemory chatMemory, int maxCount);
}
