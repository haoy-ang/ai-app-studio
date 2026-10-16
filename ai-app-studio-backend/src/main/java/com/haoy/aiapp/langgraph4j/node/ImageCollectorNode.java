package com.haoy.aiapp.langgraph4j.node;

import com.haoy.aiapp.langgraph4j.ai.ImageCollectionService;
import com.haoy.aiapp.langgraph4j.state.WorkflowContext;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;

import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 图片收集节点 —— 让 AI 自主调用图片工具，为网站收集真实素材
 * <p>
 * 这里的核心增值点：原来的方案是给网站塞随机占位图，这里是「先找图，再把图交给生成提示词」。
 * <p>
 * <b>与「静态方法反查容器」写法的一处差异</b>：依赖通过 {@link #create(ImageCollectionService)} 传入，
 * 而不是在静态方法里用 {@code SpringContextUtil.getBean(...)} 反查容器。
 * 结果完全一样，但单测可以直接 {@code new} 一个假实现塞进来，不用起 Spring 容器。
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
public class ImageCollectorNode {

    /**
     * 创建图片收集节点
     *
     * @param imageCollectionService 图片收集 AI 服务（工具调用型）
     * @return 节点动作
     */
    public static AsyncNodeAction<MessagesState<String>> create(ImageCollectionService imageCollectionService) {
        return node_async(state -> {
            WorkflowContext context = WorkflowContext.getContext(state);
            log.info("执行节点: 图片收集");

            String originalPrompt = context.getOriginalPrompt();
            String imageListStr = "";
            try {
                // ⚠️ 把 collectImages 调两遍（复制粘贴留下的 bug）会白跑一次 AI + 一轮工具调用，
                // 所以这里只调一次。
                imageListStr = imageCollectionService.collectImages(originalPrompt);
                log.info("图片收集完成，结果长度: {} 字符",
                        imageListStr == null ? 0 : imageListStr.length());
            } catch (Exception e) {
                // 图片属于「锦上添花」：收集失败也必须让工作流继续，否则一句搜索超时就能把整次生成拖垮
                log.error("图片收集失败，本次将继续生成（不带图片素材）: {}", e.getMessage());
            }

            context.setCurrentStep("图片收集");
            context.setImageListStr(imageListStr);
            return WorkflowContext.saveContext(context);
        });
    }
}
