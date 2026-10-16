package com.haoy.aiapp.ai;

import com.haoy.aiapp.model.enums.CodeGenTypeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * AI 代码生成类型智能路由测试
 * <p>
 * 会真实调用大模型（需要配置好 api-key），属手动触发的用例。
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
@SpringBootTest
public class AiCodeGenTypeRoutingServiceTest {

    @Resource
    private AiCodeGenTypeRoutingService aiCodeGenTypeRoutingService;

    @Test
    public void testRouteCodeGenType() {
        // 简单需求 -> 预期 HTML
        String userPrompt = "做一个简单的个人介绍页面";
        CodeGenTypeEnum result = aiCodeGenTypeRoutingService.routeCodeGenType(userPrompt);
        log.info("用户需求: {} -> {}", userPrompt, result == null ? null : result.getValue());

        // 中等需求 -> 预期 MULTI_FILE
        userPrompt = "做一个公司官网，需要首页、关于我们、联系我们三个页面";
        result = aiCodeGenTypeRoutingService.routeCodeGenType(userPrompt);
        log.info("用户需求: {} -> {}", userPrompt, result == null ? null : result.getValue());

        // 复杂需求 -> 预期 VUE_PROJECT
        userPrompt = "做一个电商管理系统，包含用户管理、商品管理、订单管理，需要路由和状态管理";
        result = aiCodeGenTypeRoutingService.routeCodeGenType(userPrompt);
        log.info("用户需求: {} -> {}", userPrompt, result == null ? null : result.getValue());
    }
}
