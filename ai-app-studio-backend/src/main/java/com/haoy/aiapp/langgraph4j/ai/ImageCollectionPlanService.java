package com.haoy.aiapp.langgraph4j.ai;

import com.haoy.aiapp.langgraph4j.model.ImageCollectionPlan;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 图片收集规划 AI 服务 —— 先让 AI 产出「要搜哪些图、画什么架构图」的计划，再并发执行
 * <p>
 * 这一步是「并发收集」方案的前提：把「决定做什么」和「真正去做」拆开，
 * 后者就能用 {@code CompletableFuture} 一次性并起来跑，省掉多次串行的大模型往返、显著缩短收集耗时。
 * <p>
 * 返回的是<b>结构化输出</b> {@link ImageCollectionPlan}（外层 POJO + 4 个嵌套 record）。
 * P0 探针 PROBE3 已实测：在共享 {@code chatModel}（json_object）下嵌套 record 能正常解析。
 * ⚠️ 提示词里必须出现 "json" 字样，否则 400。
 *
 * @author <a>程序员haoy</a>
 */
public interface ImageCollectionPlanService {

    /**
     * 根据用户提示词分析需要收集的图片类型和参数
     *
     * @param userPrompt 用户原始需求
     * @return 图片收集计划
     */
    @SystemMessage(fromResource = "prompt/image-collection-plan-system-prompt.txt")
    ImageCollectionPlan planImageCollection(@UserMessage String userPrompt);
}
