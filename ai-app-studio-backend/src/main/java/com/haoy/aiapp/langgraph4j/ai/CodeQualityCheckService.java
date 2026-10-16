package com.haoy.aiapp.langgraph4j.ai;

import com.haoy.aiapp.langgraph4j.model.QualityResult;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 代码质量检查 AI 服务 —— 把生成出来的代码交给 AI 做一次「能不能跑/能不能打包」的体检
 * <p>
 * 返回结构化输出 {@link QualityResult}，工作流据此决定「通过就继续构建，不通过就带着错误信息回退重生成」。
 * 这是工作流里那条「循环边」的判据来源。
 * <p>
 * ⚠️ 两个易踩的点：
 * <ol>
 *   <li>提示词里必须出现 "json" 字样（共享 {@code chatModel} 开了 json_object），否则 400。</li>
 *   <li>结构化输出的 schema 由 langchain4j 从 {@code QualityResult} 生成；字段一旦改名要同步提示词里的 JSON 示例，
 *       否则模型会按旧字段名返回、解析失败。</li>
 * </ol>
 *
 * @author <a>程序员haoy</a>
 */
public interface CodeQualityCheckService {

    /**
     * 检查代码质量
     *
     * @param codeContent 拼接好的「文件结构 + 代码内容」文本
     * @return 质量检查结果
     */
    @SystemMessage(fromResource = "prompt/code-quality-check-system-prompt.txt")
    QualityResult checkCodeQuality(@UserMessage String codeContent);
}
