package com.haoy.aiapp.langgraph4j.ai;

import com.haoy.aiapp.langgraph4j.tools.ImageSearchTool;
import com.haoy.aiapp.langgraph4j.tools.LogoGeneratorTool;
import com.haoy.aiapp.langgraph4j.tools.MermaidDiagramTool;
import com.haoy.aiapp.langgraph4j.tools.UndrawIllustrationTool;
import dev.langchain4j.agent.tool.Tool;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3 提示词契约测试 —— 不起 Spring 容器、不打 LLM，只钉住「改提示词最容易改坏」的三条硬约束。
 * <p>
 * 为什么值得单独写：
 * <ol>
 *   <li><b>提示词必须含 "json"</b>。共享 {@code chatModel} 全局开了 {@code response-format: json_object}，
 *       而 DeepSeek 要求该模式下提示词里必须出现 "json"，否则返回 400
 *       {@code Prompt must contain the word 'json' in some form ...}。
 *       这类 400 长得像网络问题，实际是提示词被改没了 —— 提前在单测里拦住，比线上排错便宜得多。</li>
 *   <li><b>提示词里写出来的工具名必须真实存在</b>。AI 只能按名字调工具，
 *       prompt 写 {@code generateArchitectureDiagram} 而代码里叫别的名字，
 *       结果就是模型一直调一个不存在的工具、白烧 token（而且报错很不显眼）。</li>
 *   <li>提示词文件必须真的在 classpath 上（打错路径 / 忘了提交 resource 都会在这一步暴露）。</li>
 * </ol>
 *
 * @author <a>程序员haoy</a>
 */
class P3PromptContractTest {

    private static String readPrompt(String resourcePath) throws Exception {
        return new ClassPathResource(resourcePath).getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * 三个提示词都必须非空、且包含 "json"（大小写不敏感）。
     */
    @Test
    void allPromptsMustMentionJson() throws Exception {
        String[] prompts = {
                "prompt/image-collection-system-prompt.txt",
                "prompt/image-collection-plan-system-prompt.txt",
                "prompt/code-quality-check-system-prompt.txt"
        };
        for (String path : prompts) {
            String content = readPrompt(path);
            assertFalse(content.isBlank(), path + " 不能是空文件");
            assertTrue(content.toLowerCase().contains("json"),
                    path + " 必须出现 \"json\" 字样，否则共享 chatModel（json_object）下必然 400");
        }
    }

    /**
     * 图片收集提示词里列出来的四个工具名，必须和四个工具类上 {@code @Tool} 的真实方法名一一对上。
     */
    @Test
    void imageCollectionPromptToolNamesMustMatchRealTools() throws Exception {
        String prompt = readPrompt("prompt/image-collection-system-prompt.txt");

        Set<String> realToolNames = new LinkedHashSet<>();
        realToolNames.addAll(toolMethodNames(ImageSearchTool.class));
        realToolNames.addAll(toolMethodNames(UndrawIllustrationTool.class));
        realToolNames.addAll(toolMethodNames(MermaidDiagramTool.class));
        realToolNames.addAll(toolMethodNames(LogoGeneratorTool.class));

        // 先钉住「这批工具名本身没变」，否则改名时这里会先失败、给出明确信号
        assertTrue(realToolNames.contains("searchContentImages"), "缺少工具 searchContentImages");
        assertTrue(realToolNames.contains("searchIllustrations"), "缺少工具 searchIllustrations");
        assertTrue(realToolNames.contains("generateArchitectureDiagram"), "缺少工具 generateArchitectureDiagram");
        assertTrue(realToolNames.contains("generateLogos"), "缺少工具 generateLogos");

        for (String toolName : realToolNames) {
            assertTrue(prompt.contains(toolName),
                    "图片收集提示词里必须出现真实工具名：" + toolName + "，否则 AI 调不动它");
        }
    }

    /**
     * 结构化的两个提示词里，JSON 字段名必须和 POJO / record 的字段一致 ——
     * 这是结构化输出能不能解析成功的前提。
     */
    @Test
    void structuredPromptFieldsMustMatchModels() throws Exception {
        String planPrompt = readPrompt("prompt/image-collection-plan-system-prompt.txt");
        for (String field : new String[]{"contentImageTasks", "illustrationTasks", "diagramTasks", "logoTasks"}) {
            assertTrue(planPrompt.contains(field), "图片收集计划提示词缺少字段名：" + field);
        }

        String qualityPrompt = readPrompt("prompt/code-quality-check-system-prompt.txt");
        for (String field : new String[]{"isValid", "errors", "suggestions"}) {
            assertTrue(qualityPrompt.contains(field), "质量检查提示词缺少字段名：" + field);
        }
    }

    /**
     * 收集一个类上所有被 {@code @Tool} 标注的方法名（也就是 AI 看到的名字）。
     */
    private static Set<String> toolMethodNames(Class<?> clazz) {
        Set<String> names = new LinkedHashSet<>();
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Tool.class)) {
                names.add(method.getName());
            }
        }
        return names;
    }
}
