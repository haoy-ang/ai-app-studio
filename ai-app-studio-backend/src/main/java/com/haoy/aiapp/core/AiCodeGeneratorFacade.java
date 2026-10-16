package com.haoy.aiapp.core;

import cn.hutool.json.JSONUtil;
import com.haoy.aiapp.ai.AiCodeGeneratorService;
import com.haoy.aiapp.ai.AiCodeGeneratorServiceFactory;
import com.haoy.aiapp.ai.model.message.AiResponseMessage;
import com.haoy.aiapp.ai.model.message.ToolExecutedMessage;
import com.haoy.aiapp.exception.BusinessException;
import com.haoy.aiapp.exception.ErrorCode;
import com.haoy.aiapp.model.enums.CodeGenTypeEnum;
import com.haoy.aiapp.parser.CodeParserExecutor;
import com.haoy.aiapp.saver.CodeFileSaverExecutor;
import dev.langchain4j.guardrail.InputGuardrailException;
import dev.langchain4j.service.TokenStream;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.File;

/**
 * AI 代码生成外观类，组合生成和保存功能
 */
@Slf4j
@Service
public class AiCodeGeneratorFacade {

    @Resource
    private AiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory;

    // ==================== 同步生成 ====================

    /**
     * 统一入口：根据类型生成并保存代码
     *
     * @param userMessage     用户提示词
     * @param codeGenTypeEnum 生成类型
     * @param appId           应用 ID
     * @return 保存的目录
     */
    public File generateAndSaveCode(String userMessage, CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "生成类型为空");
        }
        // 根据 appId + 生成类型获取对应的 AI 服务实例（不同类型用不同模型）
        AiCodeGeneratorService aiCodeGeneratorService = aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, codeGenTypeEnum);
        return switch (codeGenTypeEnum) {
            case HTML -> {
                var result = aiCodeGeneratorService.generateHtmlCode(userMessage);
                yield CodeFileSaverExecutor.executeSaver(result, CodeGenTypeEnum.HTML, appId);
            }
            case MULTI_FILE -> {
                var result = aiCodeGeneratorService.generateMultiFileCode(userMessage);
                yield CodeFileSaverExecutor.executeSaver(result, CodeGenTypeEnum.MULTI_FILE, appId);
            }
            case VUE_PROJECT -> {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Vue 工程模式暂不支持同步生成，请使用流式接口");
            }
            default -> {
                String errorMessage = "不支持的生成类型：" + codeGenTypeEnum.getValue();
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, errorMessage);
            }
        };
    }

    // ==================== 流式生成 ====================

    /**
     * 统一入口：根据类型生成并保存代码（流式）
     *
     * @param userMessage     用户提示词
     * @param codeGenTypeEnum 生成类型
     * @param appId           应用 ID
     * @return 流式响应
     */
    public Flux<String> generateAndSaveCodeStream(String userMessage, CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "生成类型为空");
        }
        // 根据 appId + 生成类型获取对应的 AI 服务实例（不同类型用不同模型）
        AiCodeGeneratorService aiCodeGeneratorService = aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, codeGenTypeEnum);
        return switch (codeGenTypeEnum) {
            case HTML -> {
                Flux<String> codeStream = aiCodeGeneratorService.generateHtmlCodeStream(userMessage);
                yield processCodeStream(codeStream, CodeGenTypeEnum.HTML, appId);
            }
            case MULTI_FILE -> {
                Flux<String> codeStream = aiCodeGeneratorService.generateMultiFileCodeStream(userMessage);
                yield processCodeStream(codeStream, CodeGenTypeEnum.MULTI_FILE, appId);
            }
            case VUE_PROJECT -> {
                // Vue 工程模式返回的是 TokenStream，需要自己适配成 Flux<JSON 消息>
                TokenStream tokenStream = aiCodeGeneratorService.generateVueProjectCodeStream(appId, userMessage);
                yield processTokenStream(tokenStream);
            }
            default -> {
                String errorMessage = "不支持的生成类型：" + codeGenTypeEnum.getValue();
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, errorMessage);
            }
        };
    }

    /**
     * 通用流式代码处理方法（原生 HTML / 多文件模式使用）
     * <p>
     * 这两类模式的流是「纯文本代码块」，直接收集完整内容后走解析器 + 保存器即可。
     */
    private Flux<String> processCodeStream(Flux<String> codeStream, CodeGenTypeEnum codeGenType, Long appId) {
        StringBuilder codeBuilder = new StringBuilder();
        return codeStream
                .doOnNext(codeBuilder::append)
                .concatWith(Flux.defer(() -> {
                    try {
                        String completeCode = codeBuilder.toString();
                        Object parsedResult = CodeParserExecutor.executeParser(completeCode, codeGenType);
                        File savedDir = CodeFileSaverExecutor.executeSaver(parsedResult, codeGenType, appId);
                        log.info("{} 流式代码保存成功，路径：{}", codeGenType.getText(), savedDir.getAbsolutePath());
                        return Flux.empty();
                    } catch (Exception e) {
                        // ⚠️ 这里绝对不能只 log 完事：原来的写法会把异常吞掉，前端照样收到 done 事件、
                        // 显示「生成完成」，但服务器上一个文件都没落盘，用户只看到一个打不开的空白应用。
                        // 正确做法是把失败原因作为一条普通消息推给前端 —— 对话气泡里能直接看到原因，
                        // 而且流仍然是「正常结束」，前端能收到 done、「转圈」能停下来。
                        log.error("{} 流式代码保存失败：{}", codeGenType.getText(), e.getMessage());
                        return Flux.just("\n\n❌ 生成失败：" + e.getMessage() + "\n\n");
                    }
                }))
                // 输入护轨拦截（超长 / 敏感词 / 注入）会让上游流直接出错。
                // 若不就地兜住，错误信号会一路冒到 Spring 的 SSE 适配层，前端 EventSource
                // 只能看到一个笼统的「连接错误」，用户根本不知道是自己输入的问题。
                .onErrorResume(error -> {
                    if (error instanceof InputGuardrailException guardrailException) {
                        log.warn("输入护轨拦截（{} 模式）：{}", codeGenType.getText(), guardrailException.getMessage());
                        return Flux.just("\n\n❌ " + guardrailException.getMessage() + "\n\n");
                    }
                    return Flux.error(error);
                });
    }

    /**
     * 将 TokenStream 适配为 Flux&lt;String&gt;（Vue 工程模式使用）
     * <p>
     * 这里其实是一个「适配器」：AI Service 返回的是 TokenStream（回调式），
     * 而门面统一对外提供 Flux（响应式流），所以要桥接一次，下游就能无差别处理了。
     * <p>
     * 每个回调都封装成统一格式的 JSON 消息再往下传，
     * 好处是下游（流处理器）靠 type 字段就能区分该怎么处理。
     * <p>
     * ⚠️ {@code start()} 是**阻塞**调用（会一直等到 AI 输出结束），
     * 所以必须 {@code subscribeOn(boundedElastic)} 把它挪到弹性线程池上执行；
     * 否则会占着 Servlet 请求线程好几分钟（本项目是 Spring MVC，返回 Flux 时由 ReactiveTypeHandler 适配成 SSE）。
     *
     * @param tokenStream AI Service 返回的流
     * @return 元素为 JSON 消息字符串的 Flux
     */
    private Flux<String> processTokenStream(TokenStream tokenStream) {
        return Flux.<String>create(sink -> {
                    tokenStream
                            // AI 输出的自然语言文本片段
                            .onPartialResponse(partialResponse ->
                                    sink.next(JSONUtil.toJsonStr(new AiResponseMessage(partialResponse))))
                            // 一次工具调用执行完成（含完整的文件路径与写入内容）
                            .onToolExecuted(toolExecution ->
                                    sink.next(JSONUtil.toJsonStr(new ToolExecutedMessage(toolExecution))))
                            // 整个响应结束
                            .onCompleteResponse(response -> sink.complete())
                            // 输入护轨被触发时，直接把原因作为一条普通消息推给前端：
                            // 如果走 sink.error，前端 EventSource 只会报一个笼统的「连接错误」，
                            // 用户完全不知道是自己输入的问题。
                            .onError(error -> {
                                if (error instanceof InputGuardrailException guardrailException) {
                                    log.warn("输入护轨拦截（Vue 模式）：{}", guardrailException.getMessage());
                                    sink.next(JSONUtil.toJsonStr(new AiResponseMessage(
                                            "\n\n❌ " + guardrailException.getMessage() + "\n\n")));
                                    sink.complete();
                                } else {
                                    sink.error(error);
                                }
                            })
                            .start();
                })
                .subscribeOn(Schedulers.boundedElastic());
    }
}