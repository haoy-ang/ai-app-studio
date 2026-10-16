package com.haoy.aiapp.langgraph4j;

import com.haoy.aiapp.ai.AiCodeGenTypeRoutingService;
import com.haoy.aiapp.core.AiCodeGeneratorFacade;
import com.haoy.aiapp.core.builder.VueProjectBuilder;
import com.haoy.aiapp.langgraph4j.ai.CodeQualityCheckService;
import com.haoy.aiapp.langgraph4j.ai.ImageCollectionPlanService;
import com.haoy.aiapp.langgraph4j.ai.ImageCollectionService;
import com.haoy.aiapp.langgraph4j.node.concurrent.ConcurrentImageCollectorNode;
import com.haoy.aiapp.langgraph4j.tools.ImageSearchTool;
import com.haoy.aiapp.langgraph4j.tools.LogoGeneratorTool;
import com.haoy.aiapp.langgraph4j.tools.MermaidDiagramTool;
import com.haoy.aiapp.langgraph4j.tools.UndrawIllustrationTool;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.springframework.stereotype.Component;

/**
 * 代码生成工作流（并发版）—— 只把「图片收集」那一步换成并发实现，整张图其余部分原样复用
 * <p>
 * 这就是 {@link CodeGenWorkflow#createImageCollectorNode()} 被刻意留成 {@code protected} 的原因：
 * 并发版<b>不需要</b>重写布线代码，只要覆写这一个方法即可。流程、条件边、循环边、刹车全都在父类里，
 * 两个版本共用同一份，改一处两边都生效。
 * <p>
 * <b>为什么值得做并发版？</b>
 * 串行版是「工具调用」形态：AI 想一步、我们做一步，搜完内容图再来一轮搜插画、再来一轮画架构图……
 * 每多一轮就多一次完整的大模型请求。并发版先用<b>一次</b> AI 调用拿到完整计划，
 * 剩下四类收集都是纯 I/O，直接 {@code CompletableFuture} 并起来跑，能省掉好几轮完整的大模型请求、显著缩短整体耗时。
 * <p>
 * ⚠️ 两个版本都注册成了 Bean（{@code codeGenWorkflow} / {@code codeGenConcurrentWorkflow}），
 * 按类型注入会撞「两个候选」，要么用 {@code @Qualifier} 指定名字，要么用 {@code @Resource(name=...)}。
 *
 * @author <a>程序员haoy</a>
 */
@Component
public class CodeGenConcurrentWorkflow extends CodeGenWorkflow {

    private final ImageCollectionPlanService imageCollectionPlanService;

    private final ImageSearchTool imageSearchTool;

    private final UndrawIllustrationTool undrawIllustrationTool;

    private final MermaidDiagramTool mermaidDiagramTool;

    private final LogoGeneratorTool logoGeneratorTool;

    public CodeGenConcurrentWorkflow(ImageCollectionService imageCollectionService,
                                     AiCodeGenTypeRoutingService aiCodeGenTypeRoutingService,
                                     AiCodeGeneratorFacade aiCodeGeneratorFacade,
                                     CodeQualityCheckService codeQualityCheckService,
                                     VueProjectBuilder vueProjectBuilder,
                                     ImageCollectionPlanService imageCollectionPlanService,
                                     ImageSearchTool imageSearchTool,
                                     UndrawIllustrationTool undrawIllustrationTool,
                                     MermaidDiagramTool mermaidDiagramTool,
                                     LogoGeneratorTool logoGeneratorTool) {
        super(imageCollectionService, aiCodeGenTypeRoutingService, aiCodeGeneratorFacade,
                codeQualityCheckService, vueProjectBuilder);
        this.imageCollectionPlanService = imageCollectionPlanService;
        this.imageSearchTool = imageSearchTool;
        this.undrawIllustrationTool = undrawIllustrationTool;
        this.mermaidDiagramTool = mermaidDiagramTool;
        this.logoGeneratorTool = logoGeneratorTool;
    }

    /**
     * 覆写图片收集节点：换成「先规划、再并发执行」的实现
     *
     * @return 并发版图片收集节点
     */
    @Override
    protected AsyncNodeAction<MessagesState<String>> createImageCollectorNode() {
        return ConcurrentImageCollectorNode.create(imageCollectionPlanService, imageSearchTool,
                undrawIllustrationTool, mermaidDiagramTool, logoGeneratorTool);
    }
}
