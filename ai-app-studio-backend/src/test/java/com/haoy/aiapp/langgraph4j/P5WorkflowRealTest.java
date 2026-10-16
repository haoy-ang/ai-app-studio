package com.haoy.aiapp.langgraph4j;

import com.haoy.aiapp.langgraph4j.state.WorkflowContext;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * P5 串行工作流<b>真机</b>端到端测试 —— 串行工作流核心价值的最终验收
 * <p>
 * 一句话需求进去，一条完整的流水线出来：真的搜图 → 真的增强提示词 → 真的路由 →
 * 真的调 AI 生成代码并落盘 → 真的判断要不要构建。
 * <p>
 * 默认<b>不跑</b>（要 `-Dp5.llm=true`），因为一次跑下来要几十秒到几分钟、且要花钱：
 * <pre>
 *   mvn -B test "-Dtest=P5WorkflowRealTest" -DfailIfNoSpecifiedTests=false -Dp5.llm=true
 * </pre>
 * ⚠️ 需要 Redis + MySQL 在线（Spring 会加载全套 Bean），且要注意 DeepSeek 的限流：
 * 若报 {@code SSLHandshakeException: Remote host terminated the handshake}，
 * 那是限流不是代码问题，等几分钟再来。
 *
 * @author <a>程序员haoy</a>
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "p5.llm", matches = "true")
class P5WorkflowRealTest {

    @Resource
    private CodeGenWorkflow codeGenWorkflow;

    /**
     * 用最省钱的 HTML 类型跑一遍完整工作流，验证「每一步都真的落地了」。
     */
    @Test
    void executeWorkflowShouldProduceRealWebsite() {
        WorkflowContext result = codeGenWorkflow.executeWorkflow("创建一个简单的个人主页");

        Assertions.assertNotNull(result, "工作流必须返回最终上下文");
        System.out.println("生成类型: " + result.getGenerationType());
        System.out.println("生成目录: " + result.getGeneratedCodeDir());
        System.out.println("构建目录: " + result.getBuildResultDir());
        System.out.println("图片收集结果长度: " + (result.getImageListStr() == null ? 0 : result.getImageListStr().length()));

        // 智能路由必须给出类型，否则后面的节点无从下手
        Assertions.assertNotNull(result.getGenerationType(), "路由节点没有产出生成类型");
        // 代码生成目录必须真实存在（说明 AI 真的把文件落了盘）
        Assertions.assertNotNull(result.getGeneratedCodeDir(), "没有生成目录");
        Assertions.assertTrue(Files.isDirectory(Paths.get(result.getGeneratedCodeDir())),
                "生成目录不存在，说明代码没有真正落盘: " + result.getGeneratedCodeDir());
        // 图片属于「增强项」，允许为空（收集失败时工作流会继续），所以只提示不强制失败
        if (result.getImageListStr() == null || result.getImageListStr().isBlank()) {
            System.out.println("⚠️ 本次没有收集到图片素材（不影响生成，但效果会差一些）");
        }
    }
}
