package com.haoy.aiapp.langgraph4j.node;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.haoy.aiapp.constant.AppConstant;
import com.haoy.aiapp.core.AiCodeGeneratorFacade;
import com.haoy.aiapp.langgraph4j.model.QualityResult;
import com.haoy.aiapp.langgraph4j.state.WorkflowContext;
import com.haoy.aiapp.model.enums.CodeGenTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import reactor.core.publisher.Flux;

import java.io.File;
import java.time.Duration;

import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 代码生成节点 —— 调 AI 生成并落盘代码，是整条工作流里最耗时的一步
 * <p>
 * 它拿到的是「增强后的提示词」（原始需求 + 图片素材清单），产出是一个磁盘目录。
 * <p>
 * <b>为什么必须 {@code blockLast}？</b>
 * 上游是流式 {@link Flux}，而工作流是「一步一步走」的：下一步质检要读真实文件，
 * 所以这里必须把流跑完才返回。这和 Web 后端里「把流直接吐给前端」是两种用法。
 * <p>
 * ⚠️ <b>超时被有意压短了</b>：本项目实测过一个已知坑 ——
 * 模型犯病时会不停重写同一批文件、SSE 永不结束（跑出过 196 次 writeFile / 19 分钟）。
 * 那种情况下 10 分钟级的超时会把工作流线程整整占满，所以这里压到 {@value #TIMEOUT_MINUTES} 分钟，
 * 并且依赖 {@code FileWriteTool} 里那道「内容一致就跳过落盘」的确定性刹车。
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
public class CodeGeneratorNode {

    /**
     * 等待流式生成完成的最长时间（分钟）—— 见类注释，比常见的 10 分钟超时更短
     */
    public static final int TIMEOUT_MINUTES = 3;

    /**
     * 创建代码生成节点
     *
     * @param codeGeneratorFacade 代码生成外观服务（流式生成 + 落盘）
     * @return 节点动作
     */
    public static AsyncNodeAction<MessagesState<String>> create(AiCodeGeneratorFacade codeGeneratorFacade) {
        return node_async(state -> {
            WorkflowContext context = WorkflowContext.getContext(state);
            log.info("执行节点: 代码生成");

            // 优先用增强后的提示词；若上一轮质检没过，则改用「修复提示词」
            String userMessage = buildUserMessage(context);

            CodeGenTypeEnum generationType = context.getGenerationType();
            if (generationType == null) {
                log.warn("状态里没有生成类型，兜底为 HTML");
                generationType = CodeGenTypeEnum.HTML;
            }
            Long appId = context.getAppId();

            log.info("开始生成代码，类型: {} ({})，appId: {}，第 {} 次生成",
                    generationType.getValue(), generationType.getText(), appId,
                    (context.getCodeGenerationCount() == null ? 0 : context.getCodeGenerationCount()) + 1);

            try {
                Flux<String> codeStream = codeGeneratorFacade.generateAndSaveCodeStream(userMessage, generationType, appId);
                // 同步等待流结束：超时或流内报错都只记日志，让下游据「目录里有没有文件」自行判断
                codeStream.blockLast(Duration.ofMinutes(TIMEOUT_MINUTES));
            } catch (Exception e) {
                log.error("代码生成异常（可能超时或模型报错），仍将尝试继续: {}", e.getMessage());
                context.setErrorMessage(e.getMessage());
            }

            // 目录规则必须和 CodeFileSaverTemplate#buildUniqueDir 完全一致：{root}/{codeType}_{appId}
            String generatedCodeDir = AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator
                    + generationType.getValue() + "_" + appId;
            log.info("AI 代码生成结束，生成目录: {}", generatedCodeDir);

            context.setCurrentStep("代码生成");
            context.setGeneratedCodeDir(generatedCodeDir);
            // 计数 +1：质检条件边靠它判断「还能不能再回退一次」，防止无限循环
            context.setCodeGenerationCount(
                    (context.getCodeGenerationCount() == null ? 0 : context.getCodeGenerationCount()) + 1);
            return WorkflowContext.saveContext(context);
        });
    }

    /**
     * 构造发给 AI 的用户消息
     * <p>
     * 两种情形：
     * <ul>
     *   <li>首次生成 / 上一轮质检通过 —— 用「原始需求 + 图片素材」的增强提示词</li>
     *   <li>上一轮质检未通过 —— 改用「错误列表 + 修复建议」的提示词，
     *       让 AI 在<b>现有代码基础上修</b>，而不是从零重写（省时间也更准）</li>
     * </ul>
     */
    static String buildUserMessage(WorkflowContext context) {
        QualityResult qualityResult = context.getQualityResult();
        if (isQualityCheckFailed(qualityResult)) {
            log.info("检测到上一轮质检失败，改用错误修复提示词");
            return buildErrorFixPrompt(qualityResult);
        }
        return StrUtil.blankToDefault(context.getEnhancedPrompt(), context.getOriginalPrompt());
    }

    /**
     * 判断质检是否「失败到需要返工」。
     * <p>
     * 判据本身就落在 {@link QualityResult#needsRework()} 里 —— 见那里的注释，
     * 关键是它<b>同时</b>要求 {@code isValid=false} 与 {@code errors} 非空。
     * 条件边 {@code routeAfterQualityCheck} 用的是同一个方法，两处不会再漂移。
     */
    static boolean isQualityCheckFailed(QualityResult qualityResult) {
        return qualityResult != null && qualityResult.needsRework();
    }

    /**
     * 把质检的错误与建议拼成修复提示词
     */
    static String buildErrorFixPrompt(QualityResult qualityResult) {
        StringBuilder errorInfo = new StringBuilder();
        errorInfo.append("\n\n## 上次生成的代码存在以下问题，请修复：\n");
        qualityResult.getErrors().forEach(error -> errorInfo.append("- ").append(error).append("\n"));
        if (CollUtil.isNotEmpty(qualityResult.getSuggestions())) {
            errorInfo.append("\n## 修复建议：\n");
            qualityResult.getSuggestions().forEach(suggestion -> errorInfo.append("- ").append(suggestion).append("\n"));
        }
        errorInfo.append("\n请根据上述问题和建议重新生成代码，确保修复所有提到的问题。");
        return errorInfo.toString();
    }
}
