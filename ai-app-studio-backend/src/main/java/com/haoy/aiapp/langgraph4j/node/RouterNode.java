package com.haoy.aiapp.langgraph4j.node;

import com.haoy.aiapp.ai.AiCodeGenTypeRoutingService;
import com.haoy.aiapp.langgraph4j.state.WorkflowContext;
import com.haoy.aiapp.model.enums.CodeGenTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;

import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 智能路由节点 —— 判断这句需求该生成 HTML / 多文件 / Vue 工程
 * <p>
 * 直接复用项目里已有的 {@link AiCodeGenTypeRoutingService}（就是创建应用时用的那个），
 * 所以工作流的判断口径和主流程完全一致，不会出现「同一个需求在两条路上被判定成不同类型」。
 * <p>
 * <b>失败兜底很重要</b>：路由要调一次 AI，可能超时/限流。这里失败时退化成 HTML 而不是抛异常 ——
 * 路由只是「选个生成方式」，不值得让整条工作流挂掉。
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
public class RouterNode {

    /**
     * 创建智能路由节点
     *
     * @param routingService 代码生成类型路由服务
     * @return 节点动作
     */
    public static AsyncNodeAction<MessagesState<String>> create(AiCodeGenTypeRoutingService routingService) {
        return node_async(state -> {
            WorkflowContext context = WorkflowContext.getContext(state);
            log.info("执行节点: 智能路由");

            CodeGenTypeEnum generationType;
            try {
                generationType = routingService.routeCodeGenType(context.getOriginalPrompt());
                log.info("AI 智能路由完成，选择类型: {} ({})",
                        generationType.getValue(), generationType.getText());
            } catch (Exception e) {
                log.error("AI 智能路由失败，使用默认 HTML 类型: {}", e.getMessage());
                generationType = CodeGenTypeEnum.HTML;
            }

            context.setCurrentStep("智能路由");
            context.setGenerationType(generationType);
            return WorkflowContext.saveContext(context);
        });
    }
}
