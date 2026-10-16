package com.haoy.aiapp.model.dto.chathistory;

import com.haoy.aiapp.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 对话历史查询请求（支持游标分页）
 *
 * @author <a>程序员haoy</a>
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class ChatHistoryQueryRequest extends PageRequest implements Serializable {

    /**
     * id
     */
    private Long id;

    /**
     * 消息内容
     */
    private String message;

    /**
     * 消息类型（user/ai）
     */
    private String messageType;

    /**
     * 应用 id
     */
    private Long appId;

    /**
     * 创建用户 id
     */
    private Long userId;

    /**
     * 游标：上一页最后一条记录的 id，只查询 id 小于该值的记录（向前加载历史消息）
     * <p>
     * ⭐ 优先用 id 而不是 createTime 做游标：id 是雪花号，单调递增且唯一；
     * 而 createTime 精度只到秒，用户消息和 AI 消息经常落在同一秒里，
     * 用 {@code createTime < 游标} 会把同秒的记录整批跳过 —— 表现就是"翻历史翻丢了一整轮对话"。
     */
    private Long lastId;

    /**
     * 游标：最后一条记录的创建时间（旧版游标，保留兼容）
     *
     * @deprecated 改用 {@link #lastId}，仅当 lastId 为空时才会走到这个分支
     */
    @Deprecated
    private LocalDateTime lastCreateTime;

    private static final long serialVersionUID = 1L;
}
