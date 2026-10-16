package com.haoy.aiapp.ai.model.message;

import dev.langchain4j.service.tool.ToolExecution;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 工具执行结果消息（工具调用完成后触发，携带完整的调用参数和执行结果）
 * <p>
 * 参数由 {@link ToolExecution#request()} 提供，是**完整**的 JSON（不像流式事件那样需要自己拼接），
 * 所以基于它就能拼出「写入了哪个文件、写了什么内容」。
 *
 * @author <a>程序员haoy</a>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class ToolExecutedMessage extends StreamMessage {

    /** 工具调用 ID，用于区分同一次对话中的多次调用 */
    private String id;

    /** 工具名称（writeFile / readFile / readDir / modifyFile / deleteFile），流处理据此找工具实例 */
    private String name;

    /** 工具调用参数（完整 JSON 字符串） */
    private String arguments;

    /** 工具执行结果 */
    private String result;

    public ToolExecutedMessage(ToolExecution toolExecution) {
        super(StreamMessageTypeEnum.TOOL_EXECUTED.getValue());
        this.id = toolExecution.request().id();
        this.name = toolExecution.request().name();
        this.arguments = toolExecution.request().arguments();
        this.result = toolExecution.result();
    }
}
