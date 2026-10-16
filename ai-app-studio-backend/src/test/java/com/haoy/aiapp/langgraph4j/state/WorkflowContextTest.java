package com.haoy.aiapp.langgraph4j.state;

import com.haoy.aiapp.langgraph4j.model.ImageCategoryEnum;
import com.haoy.aiapp.langgraph4j.model.ImageCollectionPlan;
import com.haoy.aiapp.langgraph4j.model.ImageResource;
import com.haoy.aiapp.langgraph4j.model.QualityResult;
import com.haoy.aiapp.model.enums.CodeGenTypeEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工作流状态契约测试 —— 不起 Spring 容器，只验证「状态怎么存、怎么取」这条主线。
 * <p>
 * 为什么这几条值得单独钉住：所有工作节点都是
 * {@code getContext(state) -> 改字段 -> saveContext(context)} 这个套路，
 * 一旦存取这一层出问题，表现会是「节点跑了但状态没变」，非常难查。
 *
 * @author <a>程序员haoy</a>
 */
class WorkflowContextTest {

    /**
     * saveContext 产出的 Map，塞进 MessagesState 后必须能原样取回来
     */
    @Test
    void saveAndGetContextShouldRoundTrip() {
        WorkflowContext context = WorkflowContext.builder()
                .originalPrompt("创建一个技术博客网站")
                .currentStep("初始化")
                .build();

        Map<String, Object> stateData = WorkflowContext.saveContext(context);
        assertEquals(1, stateData.size());
        assertTrue(stateData.containsKey(WorkflowContext.WORKFLOW_CONTEXT_KEY));

        MessagesState<String> state = new MessagesState<>(stateData);
        WorkflowContext loaded = WorkflowContext.getContext(state);

        assertNotNull(loaded);
        assertEquals("创建一个技术博客网站", loaded.getOriginalPrompt());
        assertEquals("初始化", loaded.getCurrentStep());
    }

    /**
     * 状态里没有存放上下文时，getContext 必须返回 null 而不是抛异常 ——
     * 节点里靠判空来处理「上一步没执行」的情况。
     */
    @Test
    void getContextShouldReturnNullWhenAbsent() {
        MessagesState<String> state = new MessagesState<>(Map.of());
        assertNull(WorkflowContext.getContext(state));
    }

    /**
     * appId 必须有默认值 0L：工作流单独跑（不接业务）时就用这个值，
     * 如果默认是 null，保存代码文件时 buildUniqueDir 会直接抛「应用 ID 不能为空」。
     */
    @Test
    void appIdShouldDefaultToZero() {
        WorkflowContext context = WorkflowContext.builder().build();
        assertNotNull(context.getAppId(), "appId 不能为 null，否则保存文件会抛业务异常");
        assertEquals(0L, context.getAppId());
    }

    /**
     * ImageCollectionPlan 要和 AI 返回的 JSON 双向对齐 ——
     * 这是「并发收集」方案的地基，字段名对不上就会静默收集不到任何图片。
     */
    @Test
    void imageCollectionPlanShouldSurviveJsonRoundTrip() throws Exception {
        String aiJson = """
                {
                  "contentImageTasks": [{"query": "编程"}, {"query": "笔记本电脑"}],
                  "illustrationTasks": [{"query": "teamwork"}],
                  "diagramTasks": [{"mermaidCode": "flowchart LR\\n A-->B", "description": "系统架构图"}],
                  "logoTasks": [{"description": "技术公司简约风格 Logo"}]
                }
                """;

        ObjectMapper mapper = new ObjectMapper();
        ImageCollectionPlan plan = mapper.readValue(aiJson, ImageCollectionPlan.class);

        assertEquals(2, plan.getContentImageTasks().size());
        assertEquals("编程", plan.getContentImageTasks().get(0).query());
        assertEquals("teamwork", plan.getIllustrationTasks().get(0).query());
        assertEquals("系统架构图", plan.getDiagramTasks().get(0).description());
        assertEquals("技术公司简约风格 Logo", plan.getLogoTasks().get(0).description());

        // 再序列化回去，字段名必须保持一致（否则提示词/缓存里对不上）
        String back = mapper.writeValueAsString(plan);
        assertTrue(back.contains("contentImageTasks"));
        assertTrue(back.contains("logoTasks"));
    }

    /**
     * 质检结果与图片资源的基本可用性
     */
    @Test
    void modelsShouldBeUsable() {
        QualityResult qualityResult = QualityResult.builder()
                .isValid(false)
                .errors(List.of("index.html 引用了不存在的 style.css"))
                .suggestions(List.of("请补全 style.css"))
                .build();
        assertEquals(false, qualityResult.getIsValid());
        assertEquals(1, qualityResult.getErrors().size());

        ImageResource resource = ImageResource.builder()
                .category(ImageCategoryEnum.ARCHITECTURE)
                .description("编程学习网站架构")
                .url("https://example.com/arch.svg")
                .build();
        assertEquals("架构图片", resource.getCategory().getText());
        assertEquals(ImageCategoryEnum.ARCHITECTURE, ImageCategoryEnum.getEnumByValue("ARCHITECTURE"));
        assertNull(ImageCategoryEnum.getEnumByValue(null));
    }

    /**
     * <b>全字段 JSON 往返</b> —— 「状态存 JSON 字符串」这个决定的验收。
     * <p>
     * 为什么这条必须钉死：工作流跑起来之后，状态每经过一个节点都要序列化一次。
     * 只要有一个字段还原不回来（枚举变 null、List 里的元素类型丢了、雪花 ID 丢精度），
     * 表现就是「工作流一路绿灯跑完，但某个字段莫名其妙是 null / 数字不对」—— 极难定位。
     * 所以这里把每种「难搞的类型」都塞进去走一遍：枚举、泛型 List、嵌套对象、19 位雪花 ID。
     */
    @Test
    void contextShouldSurviveFullJsonRoundTrip() {
        WorkflowContext original = WorkflowContext.builder()
                .currentStep("代码质量检查")
                .originalPrompt("做一个博客")
                .appId(1234567890123456789L)
                .imageListStr("{\"content\":[]}")
                .imageList(List.of(ImageResource.builder()
                        .category(ImageCategoryEnum.ARCHITECTURE)
                        .description("系统架构图")
                        .url("https://example.com/arch.svg")
                        .build()))
                .enhancedPrompt("做一个博客 + 素材")
                .generationType(CodeGenTypeEnum.VUE_PROJECT)
                .generatedCodeDir("E:/tmp/vue_project_1")
                .buildResultDir("E:/tmp/vue_project_1/dist")
                .qualityResult(QualityResult.builder()
                        .isValid(false)
                        .errors(List.of("缺少 style.css"))
                        .suggestions(List.of("补全样式文件"))
                        .build())
                .codeGenerationCount(2)
                .errorMessage("模型超时")
                .build();

        MessagesState<String> state = new MessagesState<>(WorkflowContext.saveContext(original));
        WorkflowContext loaded = WorkflowContext.getContext(state);

        assertNotNull(loaded);
        assertEquals(original.getCurrentStep(), loaded.getCurrentStep());
        assertEquals(original.getAppId(), loaded.getAppId(), "19 位雪花 ID 必须原样回来，不能丢精度");
        assertEquals(original.getGenerationType(), loaded.getGenerationType(), "枚举必须原样还原");
        assertEquals(1, loaded.getImageList().size());
        assertEquals(ImageCategoryEnum.ARCHITECTURE, loaded.getImageList().get(0).getCategory(),
                "泛型 List 里的元素类型不能丢");
        assertEquals("https://example.com/arch.svg", loaded.getImageList().get(0).getUrl());
        assertEquals(false, loaded.getQualityResult().getIsValid());
        assertEquals(List.of("缺少 style.css"), loaded.getQualityResult().getErrors());
        assertEquals(2, loaded.getCodeGenerationCount().intValue());
        assertEquals("E:/tmp/vue_project_1/dist", loaded.getBuildResultDir());
        assertEquals("模型超时", loaded.getErrorMessage());
    }

    /**
     * 兼容路径：状态里直接塞的是本加载器的对象（手写 state 调试时会发生）→ 原样返回，不做多余的 JSON 中转。
     */
    @Test
    void getContextShouldAcceptDirectlyStoredObject() {
        WorkflowContext context = WorkflowContext.builder().originalPrompt("直接塞对象").build();
        MessagesState<String> state = new MessagesState<>(
                Map.of(WorkflowContext.WORKFLOW_CONTEXT_KEY, context));

        assertSame(context, WorkflowContext.getContext(state));
    }
}
