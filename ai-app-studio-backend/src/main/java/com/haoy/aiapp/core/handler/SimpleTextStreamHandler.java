package com.haoy.aiapp.core.handler;

import cn.hutool.core.util.StrUtil;
import com.haoy.aiapp.model.entity.User;
import com.haoy.aiapp.model.enums.ChatHistoryMessageTypeEnum;
import com.haoy.aiapp.service.chathistory.ChatHistoryService;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * 简单文本流处理器
 * <p>
 * 处理 HTML / MULTI_FILE 这两种「纯文本代码块」模式的流式响应：
 * 边把内容转发给前端，边收集完整文本，流结束后落库为一条 AI 对话历史。
 * <p>
 * 这段逻辑原本散在 AppServiceImpl#chatToGenCode 里，
 * 抽出来的目的：Vue 工程模式需要另一套处理逻辑（要解析 JSON 消息），
 * 两类逻辑混在一个方法里会互相影响，所以按生成类型各管一摊。
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
public class SimpleTextStreamHandler {

    /**
     * 处理传统流（HTML / MULTI_FILE）
     *
     * @param originFlux         原始流
     * @param chatHistoryService 对话历史服务
     * @param appId              应用 ID
     * @param loginUser          登录用户
     * @return 转发给前端的流
     */
    public Flux<String> handle(Flux<String> originFlux,
                               ChatHistoryService chatHistoryService,
                               long appId, User loginUser) {
        StringBuilder aiResponseBuilder = new StringBuilder();
        return originFlux
                .map(chunk -> {
                    // 收集 AI 响应内容
                    aiResponseBuilder.append(chunk);
                    return chunk;
                })
                .doOnComplete(() -> {
                    // 流式响应完成后，添加 AI 消息到对话历史
                    String aiResponse = aiResponseBuilder.toString();
                    if (StrUtil.isNotBlank(aiResponse)) {
                        chatHistoryService.addChatMessage(appId, aiResponse,
                                ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
                    }
                })
                .doOnError(error -> {
                    // AI 回复失败也要记录，否则用户只看得到自己的提问
                    String errorMessage = "AI回复失败: " + error.getMessage();
                    chatHistoryService.addChatMessage(appId, errorMessage,
                            ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
                });
    }
}
