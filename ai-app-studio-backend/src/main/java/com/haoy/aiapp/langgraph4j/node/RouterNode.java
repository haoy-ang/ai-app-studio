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
 * <b>失败兜底分两级，这一点是实测倒逼出来的：</b>
 * <ol>
 *   <li>主路由模型（阿里云百炼 qwen-turbo）失败 → 换<b>备用模型</b>（主链路的 deepseek-chat）再判一次。
 *       理由：实测百炼会返回 {@code 429 limit_requests}，这时候模型<b>压根没机会判断</b>，
 *       直接退回 HTML 等于告诉用户「你要的 Vue 后台就是一张 HTML 页」——
 *       产物类型错了，后面每一步都白做，而且用户看不出是因为限流。</li>
 *   <li>备用也失败 → 才退回 HTML，并把「降级」写进上下文。
 *       {@code routingDegraded} 这个标记一路传到评估报告，用来区分「判断错」和「没跑成」。</li>
 * </ol>
 * <p>
 * 为什么不直接抛异常让整条工作流失败：路由只是「选个生成方式」，
 * 为一个分类调用把已经花的图片收集、提示词增强全废掉不划算。降级 + 留痕比失败更合适。
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
public class RouterNode {

    /**
     * 降级原因里保留的最大字符数 —— 上游报错常带整段 JSON，不截断会把日志和报告刷花
     */
    private static final int REASON_MAX_CHARS = 160;

    /**
     * 创建智能路由节点（只有主模型，失败直接退回 HTML）
     * <p>
     * 保留这个重载是给单元测试和「只需要一个模型」的场景用的。
     *
     * @param routingService 代码生成类型路由服务
     * @return 节点动作
     */
    public static AsyncNodeAction<MessagesState<String>> create(AiCodeGenTypeRoutingService routingService) {
        return create(routingService, null);
    }

    /**
     * 创建智能路由节点（主模型 + 备用模型）
     *
     * @param routingService         主路由服务
     * @param fallbackRoutingService 备用路由服务；为 null 时退化成单模型行为
     * @return 节点动作
     */
    public static AsyncNodeAction<MessagesState<String>> create(AiCodeGenTypeRoutingService routingService,
                                                               AiCodeGenTypeRoutingService fallbackRoutingService) {
        return node_async(state -> {
            WorkflowContext context = WorkflowContext.getContext(state);
            log.info("执行节点: 智能路由");

            String prompt = context.getOriginalPrompt();
            CodeGenTypeEnum generationType;
            try {
                generationType = routingService.routeCodeGenType(prompt);
                log.info("AI 智能路由完成，选择类型: {} ({})",
                        generationType.getValue(), generationType.getText());
            } catch (Exception primaryError) {
                generationType = routeWithFallback(context, prompt, primaryError, fallbackRoutingService);
            }

            context.setCurrentStep("智能路由");
            context.setGenerationType(generationType);
            return WorkflowContext.saveContext(context);
        });
    }

    /**
     * 主模型失败后的处理：先试备用模型，都不行才退回 HTML
     *
     * @return 最终采用的生成类型
     */
    private static CodeGenTypeEnum routeWithFallback(WorkflowContext context,
                                                     String prompt,
                                                     Exception primaryError,
                                                     AiCodeGenTypeRoutingService fallbackRoutingService) {
        String primaryReason = brief(primaryError);

        if (fallbackRoutingService != null) {
            try {
                CodeGenTypeEnum type = fallbackRoutingService.routeCodeGenType(prompt);
                log.warn("主路由模型失败，已由备用模型完成判断: {} ({}). 主模型原因: {}",
                        type.getValue(), type.getText(), primaryReason);
                context.setRoutingDegraded(true);
                context.setRoutingDegradeReason("主路由模型失败，已用备用模型判断：" + primaryReason);
                return type;
            } catch (Exception fallbackError) {
                String fallbackReason = brief(fallbackError);
                log.error("主/备用路由模型都失败，退回默认 HTML 类型。主: {}；备: {}",
                        primaryReason, fallbackReason);
                context.setRoutingDegraded(true);
                context.setRoutingDegradeReason(
                        "主/备用路由模型都失败，退回 HTML。主: " + primaryReason + "；备: " + fallbackReason);
                return CodeGenTypeEnum.HTML;
            }
        }

        log.error("AI 智能路由失败，使用默认 HTML 类型: {}", primaryReason);
        context.setRoutingDegraded(true);
        context.setRoutingDegradeReason("路由失败，退回 HTML：" + primaryReason);
        return CodeGenTypeEnum.HTML;
    }

    /**
     * 把异常压成一句话
     * <p>
     * 只取第一行并截断：上游报错里通常带着整段 JSON 和 request_id，
     * 原样写进上下文会顺着 SSE 一路传到前端和评估报告里。
     */
    private static String brief(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        String firstLine = message.split("\\R", 2)[0].trim();
        return firstLine.length() <= REASON_MAX_CHARS
                ? firstLine
                : firstLine.substring(0, REASON_MAX_CHARS) + "…";
    }
}
