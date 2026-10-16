package com.haoy.aiapp.langgraph4j.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 图片收集计划 —— 由 AI 结构化输出得到，是「并发收集」的前提
 * <p>
 * 为什么需要它？最初的做法是给 AI 一堆图片工具、让它自己边想边调，
 * 但那样要和 AI 来回多轮，慢且费 token。改成「先让 AI 一次性给出要收集什么，
 * 再由我们自己并发去执行」之后，收集环节的耗时能显著缩短、少跑好几轮大模型请求。
 * <p>
 * 外层是普通 POJO、内层任务用 {@code record} —— 这是实测验证过的组合：
 * langchain4j 的结构化输出能正确生成嵌套 JSON Schema 并反序列化回来。
 *
 * @author <a>程序员haoy</a>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImageCollectionPlan implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 内容图片搜索任务列表
     */
    private List<ImageSearchTask> contentImageTasks;

    /**
     * 插画图片搜索任务列表
     */
    private List<IllustrationTask> illustrationTasks;

    /**
     * 架构图生成任务列表
     */
    private List<DiagramTask> diagramTasks;

    /**
     * Logo 生成任务列表
     */
    private List<LogoTask> logoTasks;

    /**
     * 内容图片搜索任务
     * <p>
     * 对应 {@code ImageSearchTool.searchContentImages(String query)}
     */
    public record ImageSearchTask(String query) implements Serializable {
    }

    /**
     * 插画图片搜索任务
     * <p>
     * 对应 {@code UndrawIllustrationTool.searchIllustrations(String query)}
     */
    public record IllustrationTask(String query) implements Serializable {
    }

    /**
     * 架构图生成任务
     * <p>
     * 对应 {@code MermaidDiagramTool.generateArchitectureDiagram(String mermaidCode, String description)}
     */
    public record DiagramTask(String mermaidCode, String description) implements Serializable {
    }

    /**
     * Logo 生成任务
     * <p>
     * 对应 {@code LogoGeneratorTool.generateLogos(String description)}
     */
    public record LogoTask(String description) implements Serializable {
    }
}
