package com.haoy.aiapp.langgraph4j;

/**
 * 工作流节点名常量
 * <p>
 * 同一个节点的 key 会出现在 {@code addNode} 和 {@code addEdge} 两处，
 * 字符串写错不会编译报错，只会让图连不上。集中成常量后，改名字能一处改、处处生效，
 * 拼错也会在编译期暴露。
 *
 * @author <a>程序员haoy</a>
 */
public final class WorkflowNodeNames {

    /**
     * 图片收集
     */
    public static final String IMAGE_COLLECTOR = "image_collector";

    /**
     * 提示词增强
     */
    public static final String PROMPT_ENHANCER = "prompt_enhancer";

    /**
     * 智能路由
     */
    public static final String ROUTER = "router";

    /**
     * 代码生成
     */
    public static final String CODE_GENERATOR = "code_generator";

    /**
     * 代码质量检查
     */
    public static final String CODE_QUALITY_CHECK = "code_quality_check";

    /**
     * 项目构建
     */
    public static final String PROJECT_BUILDER = "project_builder";

    private WorkflowNodeNames() {
    }
}
