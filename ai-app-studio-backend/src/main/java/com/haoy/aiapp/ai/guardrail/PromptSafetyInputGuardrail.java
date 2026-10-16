package com.haoy.aiapp.ai.guardrail;

import com.haoy.aiapp.config.PromptGuardrailProperties;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.InputGuardrailResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Prompt 安全输入护轨
 * <p>
 * 在用户输入交给大模型<b>之前</b>做检查，拦三类东西：
 * <ol>
 *   <li>超长输入（防止把上下文塞爆）；</li>
 *   <li>敏感词（外置在 {@link PromptGuardrailProperties}）；</li>
 *   <li>Prompt 注入攻击（正则匹配「忽略以上指令」这类套路）。</li>
 * </ol>
 * 命中时返回 {@code fatal(...)} —— 语义是「直接中断本次调用并抛
 * {@code InputGuardrailException}」，而不是重试（重试同一个恶意输入没有意义）。
 * <p>
 * ⚠️ 触发方式：护轨是在 AiService 内部、Flux 建立之后执行的，所以异常不会走
 * {@code GlobalExceptionHandler} 那条线，需要 {@code AiCodeGeneratorFacade.processTokenStream}
 * 的 {@code onError} 就地转成一条普通消息推给前端（详见那边的注释）。
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
@Component
public class PromptSafetyInputGuardrail implements InputGuardrail {

    /**
     * 注入攻击模式（正则不外置：配错了很难发现，而且它是「机制」不是「策略」）
     */
    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile("(?i)ignore\\s+(?:previous|above|all)\\s+(?:instructions?|commands?|prompts?)"),
            Pattern.compile("(?i)(?:forget|disregard)\\s+(?:everything|all)\\s+(?:above|before)"),
            Pattern.compile("(?i)(?:pretend|act|behave)\\s+(?:as|like)\\s+(?:if|you\\s+are)"),
            Pattern.compile("(?i)system\\s*:\\s*you\\s+are"),
            Pattern.compile("(?i)new\\s+(?:instructions?|commands?|prompts?)\\s*:"));

    @Resource
    private PromptGuardrailProperties properties;

    @Override
    public InputGuardrailResult validate(UserMessage userMessage) {
        String input = userMessage.singleText();
        if (input == null || input.trim().isEmpty()) {
            return fatal("输入内容不能为空");
        }
        // 先查长度：超长输入没必要再跑正则，省一次遍历
        if (input.length() > properties.getMaxLength()) {
            return fatal("输入内容过长，不要超过 " + properties.getMaxLength() + " 字");
        }
        String lowerInput = input.toLowerCase();
        for (String word : properties.getSensitiveWords()) {
            if (word != null && !word.isBlank() && lowerInput.contains(word.toLowerCase())) {
                log.warn("输入护轨拦截：命中敏感词 [{}]", word);
                return fatal("输入包含不当内容，请修改后重试");
            }
        }
        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(input).find()) {
                log.warn("输入护轨拦截：命中注入模式 [{}]", pattern.pattern());
                return fatal("检测到恶意输入，请求被拒绝");
            }
        }
        return success();
    }
}
