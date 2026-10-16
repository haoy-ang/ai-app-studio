package com.haoy.aiapp.langgraph4j.ai;

import com.haoy.aiapp.langgraph4j.model.ImageCollectionPlan;
import com.haoy.aiapp.langgraph4j.model.QualityResult;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * P3 AI 服务真机测试（会真实请求 DeepSeek，有耗时和费用）
 * <p>
 * 默认<b>不跑</b>，只有显式加 {@code -Dp3.llm=true} 时才执行：
 * <pre>
 *   mvn -B test "-Dtest=P3AiServicesTest" -DfailIfNoSpecifiedTests=false -Dp3.llm=true
 * </pre>
 * 这么设计的原因：这三个调用既慢又花钱，还可能撞上 DeepSeek 的 TLS 限流
 * （{@code SSLHandshakeException: Remote host terminated the handshake}，
 * 那是限流不是代码 bug，重试三次全挂就等几分钟再来）。
 * 天天挂在 CI 里跑只会制造噪音，需要验证时手动开一次即可。
 * <p>
 * ⚠️ 跑这个类需要 Redis + MySQL 都在（Spring 上下文会加载全套 Bean）。
 *
 * @author <a>程序员haoy</a>
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "p3.llm", matches = "true")
class P3AiServicesTest {

    @Resource
    private ImageCollectionPlanService imageCollectionPlanService;

    @Resource
    private CodeQualityCheckService codeQualityCheckService;

    @Resource
    private ImageCollectionService imageCollectionService;

    /**
     * 图片收集规划：必须返回结构化对象，且至少规划出一类任务。
     */
    @Test
    void planImageCollectionShouldReturnPlan() {
        ImageCollectionPlan plan = imageCollectionPlanService.planImageCollection(
                "创建一个技术博客网站，需要展示编程教程和系统架构");
        Assertions.assertNotNull(plan, "结构化输出解析失败时 plan 会是 null");

        int total = size(plan.getContentImageTasks()) + size(plan.getIllustrationTasks())
                + size(plan.getDiagramTasks()) + size(plan.getLogoTasks());
        Assertions.assertTrue(total > 0, "至少应该规划出一类图片任务，实际一个都没有");
        System.out.println("图片收集计划：" + plan);
    }

    /**
     * 代码质量检查：能解析出 QualityResult，且 isValid 有值。
     */
    @Test
    void checkCodeQualityShouldReturnResult() {
        String codeContent = """
                # 项目文件结构和代码内容

                ## 文件: index.html

                <!DOCTYPE html>
                <html lang="zh">
                <head>
                    <meta charset="UTF-8">
                    <link rel="stylesheet" href="style.css">
                    <title>测试页</title>
                </head>
                <body>
                    <h1>Hello</h1>
                    <script src="script.js"></script>
                </body>
                </html>

                ## 文件: style.css

                body { margin: 0; font-family: sans-serif; }

                ## 文件: script.js

                console.log('ok');
                """;
        QualityResult result = codeQualityCheckService.checkCodeQuality(codeContent);
        Assertions.assertNotNull(result, "结构化输出解析失败时 result 会是 null");
        Assertions.assertNotNull(result.getIsValid(), "isValid 不能为空");
        System.out.println("质检结果：" + result);
    }

    /**
     * 图片收集（工具调用型）：必须拿到非空文本结果。
     * <p>
     * 这一步最容易出问题 —— 它要在一轮里连续调用多个图片工具，
     * 是「json_object 约束 + 多轮工具调用」共存性的真实验证点。若这里频繁失败，
     * 就该按 Plan B 换成不带 JSON 约束的独立模型。
     */
    @Test
    void collectImagesShouldReturnText() {
        String result = imageCollectionService.collectImages(
                "创建一个技术博客网站，需要展示编程教程和系统架构");
        Assertions.assertNotNull(result, "工具调用型服务不应返回 null");
        Assertions.assertFalse(result.isBlank(), "结果不应为空白");
        System.out.println("图片收集结果：" + result);
    }

    private static int size(java.util.List<?> list) {
        return list == null ? 0 : list.size();
    }
}
