package com.haoy.aiapp.langgraph4j.ai;

import com.haoy.aiapp.langgraph4j.tools.ImageSearchTool;
import com.haoy.aiapp.langgraph4j.tools.LogoGeneratorTool;
import com.haoy.aiapp.langgraph4j.tools.MermaidDiagramTool;
import com.haoy.aiapp.langgraph4j.tools.UndrawIllustrationTool;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 图片收集 AI 服务工厂 —— 注入图片工具「四件套」
 * <p>
 * 这里是<b>工具调用型</b>服务，四个工具都由 AI 自主决定是否调用、调用几次。
 * 工具本身是 {@code @Component} Bean，由 Spring 注入，而不是在工厂里 new ——
 * 因为工具内部依赖配置项（API Key、mmdc 路径等），需要走 Spring 的环境注入。
 * <p>
 * ⚠️ 关于模型：直接复用共享的 {@code chatModel}（全局带 {@code response-format: json_object}）。
 * 看起来「json_object 约束 + 工具调用」是个未定义组合，但 P0 探针已实测两者可以共存
 * （只要提示词里出现 "json" 字样）。不要一上来就自己 {@code OpenAiChatModel.builder()}
 * 造一个无约束模型，那是过度设计。只有当多轮工具调用真的被 JSON 约束打断时，才切独立模型。
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
@Configuration
public class ImageCollectionServiceFactory {

    @Resource
    private ChatModel chatModel;

    @Resource
    private ImageSearchTool imageSearchTool;

    @Resource
    private UndrawIllustrationTool undrawIllustrationTool;

    @Resource
    private MermaidDiagramTool mermaidDiagramTool;

    @Resource
    private LogoGeneratorTool logoGeneratorTool;

    /**
     * 创建图片收集 AI 服务
     */
    @Bean
    public ImageCollectionService createImageCollectionService() {
        return AiServices.builder(ImageCollectionService.class)
                .chatModel(chatModel)
                .tools(
                        imageSearchTool,
                        undrawIllustrationTool,
                        mermaidDiagramTool,
                        logoGeneratorTool
                )
                // 模型偶尔会自己编一个不存在的工具名。默认行为是直接抛异常中断整次生成，
                // 这里改成「把错误当成一条工具结果回给模型」，让它自己纠正后继续 —— 图片收集不值得为这个失败。
                .hallucinatedToolNameStrategy(toolExecutionRequest ->
                        ToolExecutionResultMessage.from(toolExecutionRequest,
                                "Error: there is no tool called " + toolExecutionRequest.name()))
                .build();
    }
}
