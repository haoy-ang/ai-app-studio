package com.haoy.aiapp.core.handler;

import com.haoy.aiapp.model.entity.User;
import com.haoy.aiapp.model.enums.CodeGenTypeEnum;
import com.haoy.aiapp.service.chathistory.ChatHistoryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 流处理器执行器
 * <p>
 * 按代码生成类型选择对应的流处理器：
 * <ul>
 *   <li>HTML / MULTI_FILE —— 纯文本流，走 {@link SimpleTextStreamHandler}</li>
 *   <li>VUE_PROJECT —— JSON 消息流（含工具调用），走 {@link JsonMessageStreamHandler}</li>
 * </ul>
 * 这样 AppService 不用关心流里到底是什么格式，只管把流交给执行器。
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
@Component
public class StreamHandlerExecutor {

    @Resource
    private JsonMessageStreamHandler jsonMessageStreamHandler;

    /**
     * 根据生成类型处理流
     *
     * @param originFlux         原始流
     * @param chatHistoryService 对话历史服务
     * @param appId              应用 ID
     * @param loginUser          登录用户
     * @param codeGenType        代码生成类型
     * @return 转发给前端的流
     */
    public Flux<String> doExecute(Flux<String> originFlux,
                                  ChatHistoryService chatHistoryService,
                                  long appId, User loginUser,
                                  CodeGenTypeEnum codeGenType) {
        return switch (codeGenType) {
            // JSON 消息流需要注入的组件，用单例 Bean
            case VUE_PROJECT -> jsonMessageStreamHandler.handle(originFlux, chatHistoryService, appId, loginUser);
            // 简单文本处理器无状态，直接 new 即可
            case HTML, MULTI_FILE -> new SimpleTextStreamHandler().handle(originFlux, chatHistoryService, appId, loginUser);
        };
    }
}
