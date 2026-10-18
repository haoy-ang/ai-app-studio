package com.haoy.aiapp.ai;

import com.haoy.aiapp.ai.model.HtmlCodeResult;
import com.haoy.aiapp.ai.model.MultiFileCodeResult;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * AI 代码生成服务测试（直接调用大模型）
 * <p>
 * 注意：会真实请求 DeepSeek，有调用耗时和费用。只想验证解析、保存逻辑时不用跑这个类。
 * <p>
 * <b>打了 {@code requires-ai} 标签，默认不执行</b>（见 pom 里的 test.groups）：
 * 它验证的是「模型今天能不能返回合法 JSON」，不是本项目代码的正确性。
 * 结构化输出解析失败（{@code OutputParsingException}）在这里是红，但它反映的是模型行为波动，
 * 不构成本项目的回归信号 —— 真正的解析容错能力应该用固定字符串测（见 {@code CodeParserTest}）。
 * <p>
 * 手动跑：{@code mvn test -Dtest=AiCodeGeneratorServiceTest -Dtest.groups=requires-ai}
 *
 * @author <a>程序员haoy</a>
 */
@Tag("requires-ai")
@SpringBootTest
class AiCodeGeneratorServiceTest {

    @Resource
    private AiCodeGeneratorService aiCodeGeneratorService;

    @Test
    void generateHtmlCode() {
        HtmlCodeResult result = aiCodeGeneratorService.generateHtmlCode("做个工作记录小工具");
        Assertions.assertNotNull(result);
        // 结构化输出真的拿到代码才算通过，只有对象不为空可能是解析失败后的空壳
        Assertions.assertNotNull(result.getHtmlCode());
    }

    @Test
    void generateMultiFileCode() {
        MultiFileCodeResult result = aiCodeGeneratorService.generateMultiFileCode("做个留言板");
        Assertions.assertNotNull(result);
        // CSS、JS 允许为空（多文件保存器的校验也只要求 HTML 不为空），所以这里只断言 HTML
        Assertions.assertNotNull(result.getHtmlCode());
    }
}
