package com.haoy.aiapp.ai.model.message;

import lombok.Getter;

/**
 * 流式消息类型枚举
 * <p>
 * 目前只有两种类型，因为 langchain4j 1.1.0 的 {@code TokenStream} 只暴露了
 * {@code onPartialResponse} 和 {@code onToolExecuted} 两个可用回调。
 * <p>
 * 还有一个 TOOL_REQUEST（工具调用请求，用于在「AI 刚开始写文件」时就提示用户），
 * 它依赖 {@code onPartialToolExecutionRequest} 事件 —— 该事件需要 langchain4j 1.2+，
 * 本项目的 1.1.0 没有，所以不定义（定义了也永远匹配不上）。
 * 将来升级到 1.2+ 后，补上枚举值 + 消息类 + 一个 switch 分支即可，落库格式无需改动。
 *
 * @author <a>程序员haoy</a>
 */
@Getter
public enum StreamMessageTypeEnum {

    AI_RESPONSE("ai_response", "AI 响应"),
    TOOL_EXECUTED("tool_executed", "工具执行结果");

    private final String value;
    private final String text;

    StreamMessageTypeEnum(String value, String text) {
        this.value = value;
        this.text = text;
    }

    /**
     * 根据 value 获取枚举
     *
     * @param value 枚举值
     * @return 匹配的枚举，找不到返回 null
     */
    public static StreamMessageTypeEnum getEnumByValue(String value) {
        for (StreamMessageTypeEnum typeEnum : values()) {
            if (typeEnum.value.equals(value)) {
                return typeEnum;
            }
        }
        return null;
    }
}
