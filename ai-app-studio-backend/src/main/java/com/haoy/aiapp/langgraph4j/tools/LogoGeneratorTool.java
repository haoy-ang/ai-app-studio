package com.haoy.aiapp.langgraph4j.tools;

import cn.hutool.core.util.StrUtil;
import com.haoy.aiapp.langgraph4j.model.ImageResource;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Logo 生成工具 —— <b>当前为占位实现</b>
 * <p>
 * Logo 需要按描述定制生成，只能用文生图模型（例如阿里云百炼 DashScope 的
 * {@code wan2.2-t2i-flash}）。本项目暂时不接入，原因有三：
 * <ol>
 *   <li><b>它是付费的</b>，而换来的只是「网站多一个 Logo」，性价比低；</li>
 *   <li>DashScope 的文生图 API 有新旧两套通道，{@code ImageSynthesis}
 *       属于老通道，新模型可能需要走 {@code MultiModalConversation}，
 *       直接复用旧写法不一定能通 —— 接入前必须先写探针验证；</li>
 *   <li>它返回的 OSS 链接<b>只有 24 小时有效</b>，必须下载后转存到自己的对象存储，
 *       否则用户第二天打开网站会看到一片裂图。</li>
 * </ol>
 * <p>
 * 之所以保留这个类而不是直接删掉：工作流的图片工具集是「四件套」，
 * 少了它 {@code AiServices.tools(...)} 的参数就不完整，将来要接的时候还得回头改工厂。
 * 占位实现让整条工作流的结构保持完整，只是这一路暂时收集不到图。
 * <p>
 * <b>接入步骤（将来要做时）：</b>
 * <ol>
 *   <li>pom 加 {@code com.alibaba:dashscope-sdk-java}，yml 配 {@code dashscope.api-key} 和 {@code dashscope.image-model}</li>
 *   <li>先写个 20 行探针确认「类名 + 模型名」组合真的能出图，再写正式实现</li>
 *   <li>拿到结果后<b>下载图片 → 上传 COS → 用 COS 地址</b>，不要把 OSS 临时链接直接返回</li>
 * </ol>
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
@Component
public class LogoGeneratorTool {

    /**
     * 根据描述生成 Logo 设计图片，用于网站品牌标识
     * <p>
     * 当前返回空列表 = 「这一路没收集到 Logo」，下游的提示词增强节点会自然地跳过它，
     * 不会因为缺 Logo 而报错。AI 看到空结果也不会反复重试调用。
     *
     * @param description Logo 设计描述（名称、行业、风格等）
     * @return 固定返回空列表
     */
    @Tool("根据描述生成 Logo 设计图片，用于网站品牌标识")
    public List<ImageResource> generateLogos(@P("Logo 设计描述，如名称、行业、风格等，尽量详细") String description) {
        if (StrUtil.isBlank(description)) {
            return new ArrayList<>();
        }
        log.info("Logo 生成未接入（DashScope 付费文生图），本次跳过。请求描述：{}", description);
        return new ArrayList<>();
    }
}
