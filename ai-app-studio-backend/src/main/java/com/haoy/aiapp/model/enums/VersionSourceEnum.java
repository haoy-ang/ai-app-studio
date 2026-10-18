package com.haoy.aiapp.model.enums;

import lombok.Getter;

/**
 * 版本来源
 * <p>
 * 记下来是为了在版本列表里说清「这条是怎么来的」：
 * 用户自己对话改出来的，还是工作流跑出来的。回滚产生的版本用 ROLLBACK 标出来，
 * 免得用户以为是自己哪次对话生成的结果。
 *
 * @author <a>程序员haoy</a>
 */
@Getter
public enum VersionSourceEnum {

    /** 对话生成（用户在聊天框里让 AI 改） */
    CHAT("对话生成", "chat"),

    /** AI 工作流生成 */
    WORKFLOW("工作流", "workflow"),

    /** 回滚产生（回滚后立刻再生成一次时，用来说明上一跳是回滚） */
    ROLLBACK("回滚", "rollback");

    private final String text;
    private final String value;

    VersionSourceEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }
}
