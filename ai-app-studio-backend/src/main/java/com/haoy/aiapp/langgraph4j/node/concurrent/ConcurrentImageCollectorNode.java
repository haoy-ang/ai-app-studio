package com.haoy.aiapp.langgraph4j.node.concurrent;

import com.haoy.aiapp.langgraph4j.ai.ImageCollectionPlanService;
import com.haoy.aiapp.langgraph4j.model.ImageCollectionPlan;
import com.haoy.aiapp.langgraph4j.model.ImageResource;
import com.haoy.aiapp.langgraph4j.state.WorkflowContext;
import com.haoy.aiapp.langgraph4j.tools.ImageSearchTool;
import com.haoy.aiapp.langgraph4j.tools.LogoGeneratorTool;
import com.haoy.aiapp.langgraph4j.tools.MermaidDiagramTool;
import com.haoy.aiapp.langgraph4j.tools.UndrawIllustrationTool;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.springframework.util.StopWatch;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 图片收集节点（并发版）—— 「先规划、再并发执行」的形态
 * <p>
 * <b>为什么不继续用工具调用？</b>
 * 工具调用是「AI 想一步、我们做一步」的多轮交互：搜完内容图再来一轮搜插画、再来一轮画架构图……
 * 每多一轮就多一次完整的大模型请求。实测下来这条路又慢又贵。
 * <p>
 * 换个思路：先用<b>一次</b> AI 调用拿到「要搜哪些关键词、画什么架构图」的完整计划（结构化输出），
 * 剩下四类收集工作就是纯粹的 I/O，直接用 {@code CompletableFuture} 并起来跑 —— 这就是「规划 + 并发执行」。
 * <p>
 * <b>两个实现细节值得一提：</b>
 * <ol>
 *   <li><b>用虚拟线程池而不是默认的 ForkJoinPool</b>。这些任务是 HTTP 请求、属于 I/O 阻塞型，
 *       丢进以 CPU 核数为并行度的公共池会把池占满、影响其他用到它的代码。虚拟线程「一个任务一个」、
 *       阻塞成本极低，正好对口。用 try-with-resources 保证执行器一定被关闭。</li>
 *   <li><b>单个任务失败不影响整体</b>。每类图各自 try/catch，拿不到就只是少几张图，
 *       不能因为插画接口超时就让整次网站生成失败。</li>
 * </ol>
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
public class ConcurrentImageCollectorNode {

    /**
     * 创建并发版图片收集节点
     *
     * @param planService           图片收集规划服务（结构化输出）
     * @param imageSearchTool       内容图搜索
     * @param undrawIllustrationTool 插画搜索
     * @param mermaidDiagramTool    架构图生成
     * @param logoGeneratorTool     Logo 生成（当前为占位实现）
     * @return 节点动作
     */
    public static AsyncNodeAction<MessagesState<String>> create(ImageCollectionPlanService planService,
                                                               ImageSearchTool imageSearchTool,
                                                               UndrawIllustrationTool undrawIllustrationTool,
                                                               MermaidDiagramTool mermaidDiagramTool,
                                                               LogoGeneratorTool logoGeneratorTool) {
        return node_async(state -> {
            WorkflowContext context = WorkflowContext.getContext(state);
            log.info("执行节点: 图片收集（规划 + 并发）");

            String originalPrompt = context.getOriginalPrompt();
            List<ImageResource> collectedImages = new ArrayList<>();

            StopWatch stopWatch = new StopWatch();
            stopWatch.start();
            try {
                // 第一步：一次 AI 调用拿到完整计划
                ImageCollectionPlan plan = planService.planImageCollection(originalPrompt);
                log.info("获取到图片收集计划，开始并发执行");

                // 第二步：把计划里的四类任务全部提交，并起来跑
                List<CompletableFuture<List<ImageResource>>> futures = new ArrayList<>();
                addContentImageTasks(plan, imageSearchTool, futures);
                addIllustrationTasks(plan, undrawIllustrationTool, futures);
                addDiagramTasks(plan, mermaidDiagramTool, futures);
                addLogoTasks(plan, logoGeneratorTool, futures);

                // 第三步：等齐、汇总
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                for (CompletableFuture<List<ImageResource>> future : futures) {
                    List<ImageResource> images = future.get();
                    if (images != null) {
                        collectedImages.addAll(images);
                    }
                }
                log.info("并发图片收集完成，共收集到 {} 张图片", collectedImages.size());
            } catch (Exception e) {
                // 图片属于增强项：收集失败也要让工作流继续往下走
                log.error("图片收集失败，本次将继续生成（不带图片素材）: {}", e.getMessage());
            } finally {
                stopWatch.stop();
                log.info("图片收集总耗时: {} ms", stopWatch.getTotalTimeMillis());
            }

            context.setCurrentStep("图片收集");
            context.setImageList(collectedImages);
            return WorkflowContext.saveContext(context);
        });
    }

    private static void addContentImageTasks(ImageCollectionPlan plan,
                                             ImageSearchTool tool,
                                             List<CompletableFuture<List<ImageResource>>> futures) {
        if (plan == null || plan.getContentImageTasks() == null) {
            return;
        }
        for (ImageCollectionPlan.ImageSearchTask task : plan.getContentImageTasks()) {
            futures.add(supplySafely("内容图:" + task.query(), () -> tool.searchContentImages(task.query())));
        }
    }

    private static void addIllustrationTasks(ImageCollectionPlan plan,
                                             UndrawIllustrationTool tool,
                                             List<CompletableFuture<List<ImageResource>>> futures) {
        if (plan == null || plan.getIllustrationTasks() == null) {
            return;
        }
        for (ImageCollectionPlan.IllustrationTask task : plan.getIllustrationTasks()) {
            futures.add(supplySafely("插画:" + task.query(), () -> tool.searchIllustrations(task.query())));
        }
    }

    private static void addDiagramTasks(ImageCollectionPlan plan,
                                        MermaidDiagramTool tool,
                                        List<CompletableFuture<List<ImageResource>>> futures) {
        if (plan == null || plan.getDiagramTasks() == null) {
            return;
        }
        for (ImageCollectionPlan.DiagramTask task : plan.getDiagramTasks()) {
            futures.add(supplySafely("架构图:" + task.description(),
                    () -> tool.generateArchitectureDiagram(task.mermaidCode(), task.description())));
        }
    }

    private static void addLogoTasks(ImageCollectionPlan plan,
                                     LogoGeneratorTool tool,
                                     List<CompletableFuture<List<ImageResource>>> futures) {
        if (plan == null || plan.getLogoTasks() == null) {
            return;
        }
        for (ImageCollectionPlan.LogoTask task : plan.getLogoTasks()) {
            futures.add(supplySafely("Logo:" + task.description(), () -> tool.generateLogos(task.description())));
        }
    }

    /**
     * 提交一个异步任务，并把「单个任务炸掉」隔离在本方法内
     * <p>
     * 执行器在每个任务内部创建/关闭：任务数是「计划里的图片条数」，量很小（个位数），
     * 创建虚拟线程执行器的成本可以忽略，换来的是绝不会有执行器泄漏。
     *
     * @param taskName 用于日志定位的名称
     * @param supplier 真正的收集动作
     * @return 该任务的异步结果（失败时是空列表）
     */
    private static CompletableFuture<List<ImageResource>> supplySafely(String taskName,
                                                                      java.util.function.Supplier<List<ImageResource>> supplier) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return supplier.get();
            } catch (Exception e) {
                log.warn("图片任务失败，已跳过（{}）：{}", taskName, e.getMessage());
                return new ArrayList<ImageResource>();
            }
        }, executor).whenComplete((result, throwable) -> executor.shutdown());
    }
}
