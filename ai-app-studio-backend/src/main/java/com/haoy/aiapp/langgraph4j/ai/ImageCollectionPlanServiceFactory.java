package com.haoy.aiapp.langgraph4j.ai;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 图片收集规划 AI 服务工厂 —— 复用共享 {@code chatModel}（结构化输出）
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
@Configuration
public class ImageCollectionPlanServiceFactory {

    @Resource
    private ChatModel chatModel;

    /**
     * 创建图片收集规划 AI 服务
     */
    @Bean
    public ImageCollectionPlanService createImageCollectionPlanService() {
        return AiServices.builder(ImageCollectionPlanService.class)
                .chatModel(chatModel)
                .build();
    }
}
