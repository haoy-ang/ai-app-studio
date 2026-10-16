package com.haoy.aiapp.ai;

import com.haoy.aiapp.ai.model.HtmlCodeResult;
import com.haoy.aiapp.ai.model.MultiFileCodeResult;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * AI 代码生成服务测试（直接调用大模型）
 * <p>
 * 注意：会真实请求 DeepSeek，有调用耗时和费用。只想验证解析、保存逻辑时不用跑这个类。
 *
 * @author <a>程序员haoy</a>
 */
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
