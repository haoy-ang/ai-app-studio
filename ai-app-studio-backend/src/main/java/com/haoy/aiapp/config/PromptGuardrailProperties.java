package com.haoy.aiapp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Prompt 安全审查配置
 * <p>
 * 把敏感词硬编码在护轨类里不利于调整。这里外置成配置，因为中英混排的关键词很容易误伤 ——
 * 比如用户想做一个「暗色黑客风格官网」，中文「黑客」不在表里、英文 hack 却会把请求拦下来。
 * 外置之后改一行 yml 就能调，不用重新编译。
 * <p>
 * 注意这是「策略」可以外置，但注入攻击的<b>正则</b>留在代码里（见 {@code PromptSafetyInputGuardrail}）：
 * 它是「机制」，写错了极难发现，不适合让运维随手改。
 *
 * @author <a>程序员haoy</a>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "prompt-guardrail")
public class PromptGuardrailProperties {

    /** 用户输入的最大长度 */
    private int maxLength = 1000;

    /** 敏感词表（命中即拒绝） */
    private List<String> sensitiveWords = List.of(
            "忽略之前的指令", "ignore previous instructions", "ignore above",
            "破解", "hack", "绕过", "bypass", "越狱", "jailbreak");
}
