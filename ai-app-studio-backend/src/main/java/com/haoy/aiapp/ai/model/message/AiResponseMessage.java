package com.haoy.aiapp.ai.model.message;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * AI 响应消息（AI 输出的自然语言文本片段）
 *
 * @author <a>程序员haoy</a>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class AiResponseMessage extends StreamMessage {

    /**
     * AI 本轮输出的文本片段
     */
    private String data;

    public AiResponseMessage(String data) {
        super(StreamMessageTypeEnum.AI_RESPONSE.getValue());
        this.data = data;
    }
}
