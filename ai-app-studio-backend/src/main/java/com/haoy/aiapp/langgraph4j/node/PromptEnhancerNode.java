package com.haoy.aiapp.langgraph4j.node;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.haoy.aiapp.langgraph4j.model.ImageResource;
import com.haoy.aiapp.langgraph4j.state.WorkflowContext;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;

import java.util.List;

import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 提示词增强节点 —— 把收集到的图片素材拼进原始需求，再交给代码生成
 * <p>
 * 只是字符串拼接、不调 AI，所以这个节点没有依赖、也不会有失败分支。
 * <p>
 * <b>为什么要兼容两种图片载体？</b>
 * {@code imageList}（结构化 List）对应「并发收集」方案，{@code imageListStr}（AI 返回的文本）
 * 对应「工具调用收集」方案。两条路都写好后，切换方案不用改这个节点 ——
 * 顺带解决了「以后想换收集方式还得回来改拼接逻辑」的问题。
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
public class PromptEnhancerNode {

    /**
     * 创建提示词增强节点
     *
     * @return 节点动作
     */
    public static AsyncNodeAction<MessagesState<String>> create() {
        return node_async(state -> {
            WorkflowContext context = WorkflowContext.getContext(state);
            log.info("执行节点: 提示词增强");

            String originalPrompt = context.getOriginalPrompt();
            String imageListStr = context.getImageListStr();
            List<ImageResource> imageList = context.getImageList();

            StringBuilder enhancedPromptBuilder = new StringBuilder(StrUtil.nullToEmpty(originalPrompt));
            if (CollUtil.isNotEmpty(imageList) || StrUtil.isNotBlank(imageListStr)) {
                enhancedPromptBuilder.append("\n\n## 可用素材资源\n");
                enhancedPromptBuilder.append("请在生成网站时使用以下图片资源，将这些图片合理地嵌入到网站的相应位置中。\n");
                if (CollUtil.isNotEmpty(imageList)) {
                    for (ImageResource image : imageList) {
                        enhancedPromptBuilder.append("- ")
                                .append(image.getCategory().getText())
                                .append("：")
                                .append(image.getDescription())
                                .append("（")
                                .append(image.getUrl())
                                .append("）\n");
                    }
                } else {
                    enhancedPromptBuilder.append(imageListStr);
                }
            }
            String enhancedPrompt = enhancedPromptBuilder.toString();

            context.setCurrentStep("提示词增强");
            context.setEnhancedPrompt(enhancedPrompt);
            log.info("提示词增强完成，增强后长度: {} 字符", enhancedPrompt.length());
            return WorkflowContext.saveContext(context);
        });
    }
}
