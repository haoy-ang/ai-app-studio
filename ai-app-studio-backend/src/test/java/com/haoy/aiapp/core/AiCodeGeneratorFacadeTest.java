package com.haoy.aiapp.core;

import com.haoy.aiapp.model.enums.CodeGenTypeEnum;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.File;

/**
 * AI 代码生成门面测试：生成 → 解析 → 保存 全链路
 * <p>
 * 注意：会真实请求 DeepSeek，有调用耗时和费用。
 * 运行后可以在 tmp/code_output/multi_file_1/ 下看到生成的文件。
 * <p>
 * 正因为它花钱又依赖上游可用性，所以打了 {@code requires-ai} 标签 ——
 * 默认的 {@code mvn test} 不跑它，要跑就显式指定：
 * <pre>
 *   mvn test -Dtest=AiCodeGeneratorFacadeTest -Dtest.groups=requires-ai
 * </pre>
 *
 * @author <a>程序员haoy</a>
 */
@Tag("requires-ai")
@SpringBootTest
class AiCodeGeneratorFacadeTest {

    /**
     * 测试用应用 id，只用来拼接保存目录名（{codeGenType}_{appId}），不需要数据库里真实存在
     */
    private static final Long TEST_APP_ID = 1L;

    @Resource
    private AiCodeGeneratorFacade aiCodeGeneratorFacade;

    @Test
    void generateAndSaveCode() {
        File savedDir = aiCodeGeneratorFacade.generateAndSaveCode("任务记录网站", CodeGenTypeEnum.MULTI_FILE, TEST_APP_ID);
        Assertions.assertNotNull(savedDir);
        Assertions.assertTrue(savedDir.exists());
        Assertions.assertTrue(savedDir.isDirectory());
        // 多文件模式固定产出这三个文件
        Assertions.assertTrue(new File(savedDir, "index.html").exists());
        Assertions.assertTrue(savedDir.getName().equals(CodeGenTypeEnum.MULTI_FILE.getValue() + "_" + TEST_APP_ID));
    }
}
