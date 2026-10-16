package com.haoy.aiapp.ai;

import com.haoy.aiapp.ai.model.HtmlCodeResult;
import com.haoy.aiapp.ai.model.MultiFileCodeResult;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import reactor.core.publisher.Flux;

public interface AiCodeGeneratorService {

    @SystemMessage(fromResource = "prompt/codegen-html-system-prompt.txt")
    HtmlCodeResult generateHtmlCode(String userMessage);

    @SystemMessage(fromResource = "prompt/codegen-multi-file-system-prompt.txt")
    MultiFileCodeResult generateMultiFileCode(String userMessage);

    /**
     * 生成 HTML 代码（流式）
     */
    @SystemMessage(fromResource = "prompt/codegen-html-system-prompt.txt")
    Flux<String> generateHtmlCodeStream(String userMessage);

    /**
     * 生成多文件代码（流式）
     */
    @SystemMessage(fromResource = "prompt/codegen-multi-file-system-prompt.txt")
    Flux<String> generateMultiFileCodeStream(String userMessage);

    /**
     * 生成 Vue 项目代码（流式）
     * <p>
     * 使用推理模型 + FileWriteTool 工具调用，让 AI 分批写入多个 Vue 项目文件。
     * <p>
     * 这里必须返回 {@link TokenStream} 而不是 {@code Flux<String>}：
     * 返回 Flux 时由 langchain4j-reactor 适配，只会把 AI 的**文本增量**转发出来，
     * 工具调用事件（工具名 / 文件路径 / 写入内容）会全部丢失，前端就看不到任何写文件过程。
     * TokenStream 才能监听 {@code onToolExecuted}，拿到完整的工具调用参数。
     *
     * @param appId       当前应用 ID（作为 LangChain4j 记忆 ID，实现多应用对话隔离，工具内也能拿到）
     * @param userMessage 用户消息
     * @return 生成过程的流式响应
     */
    @SystemMessage(fromResource = "prompt/codegen-vue-project-system-prompt.txt")
    TokenStream generateVueProjectCodeStream(@MemoryId long appId, @UserMessage String userMessage);
}