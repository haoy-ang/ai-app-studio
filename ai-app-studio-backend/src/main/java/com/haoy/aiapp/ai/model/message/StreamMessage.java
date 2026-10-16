package com.haoy.aiapp.ai.model.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 流式消息响应基类
 * <p>
 * Vue 工程生成模式下，AI 的输出不再是纯文本，而是「AI 文本 + 工具调用」混合的多种消息，
 * 因此统一封装成 JSON 字符串往下游传，靠 {@code type} 字段区分消息类别。
 * <p>
 * 注意：子类必须提供无参构造函数（{@code @NoArgsConstructor}），
 * 否则下游用 JSON 反序列化时会失败。
 *
 * @author <a>程序员haoy</a>
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class StreamMessage {

    /**
     * 消息类别，取值见 {@link StreamMessageTypeEnum}
     */
    private String type;
}
