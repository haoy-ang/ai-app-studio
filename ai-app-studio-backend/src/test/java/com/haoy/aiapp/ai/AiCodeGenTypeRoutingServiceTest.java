package com.haoy.aiapp.ai;

import com.haoy.aiapp.model.enums.CodeGenTypeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * AI 代码生成类型智能路由测试
 * <p>
 * 会真实调用大模型（需要配置好 api-key），属手动触发的用例。
 * <p>
 * <b>打了 {@code requires-ai} 标签，默认不执行。</b>为什么必须隔离：
 * 路由走的是阿里云百炼 qwen-turbo，它是<b>按分钟限请求数</b>的（429 {@code limit_requests}），
 * 本类一口气连调 3 次、再叠加其它用例，很容易撞上限流；而一旦 429 抛出来，
 * {@code mvn test} 就变成红的 —— 但项目代码一行都没错。
 * <p>
 * <b>为什么只断言「不为 null」、不硬断言具体类型？</b>
 * 因为「公司官网三个页面该判多文件还是 Vue」这类边界本身就没有标准答案，
 * 模型今天判 A 明天判 B 都算合理。把答案写死，测试就会周期性、无意义地变红。
 * <p>
 * 真正要的指标是<b>路由准确率</b>，它需要「一批固定用例 + 逐条统计」，
 * 那是评估体系（L2 的 {@code routingAccuracy}）该干的事，不是单个断言能表达的。
 * <p>
 * 手动跑：{@code mvn test -Dtest=AiCodeGenTypeRoutingServiceTest -Dtest.groups=requires-ai}
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
@Tag("requires-ai")
@SpringBootTest
public class AiCodeGenTypeRoutingServiceTest {

    @Resource
    private AiCodeGenTypeRoutingService aiCodeGenTypeRoutingService;

    @Test
    public void testRouteCodeGenType() {
        assertRoute("做一个简单的个人介绍页面");
        assertRoute("做一个公司官网，需要首页、关于我们、联系我们三个页面");
        assertRoute("做一个电商管理系统，包含用户管理、商品管理、订单管理，需要路由和状态管理");
    }

    /**
     * 跑一次路由并断言链路通
     * <p>
     * 只验证「能拿到一个合法的生成类型」—— 这已经足够说明模型配置、Key、结构化输出都没问题。
     *
     * @param prompt 用户需求
     */
    private void assertRoute(String prompt) {
        CodeGenTypeEnum result = aiCodeGenTypeRoutingService.routeCodeGenType(prompt);
        log.info("用户需求: {} -> {}", prompt, result == null ? null : result.getValue());
        Assertions.assertNotNull(result, "路由返回空，可能是模型配置或 Key 有问题");
    }
}
