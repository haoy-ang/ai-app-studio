package com.haoy.aiapp.langgraph4j.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 代码质检结果 —— 由「代码质检」工作节点调用 AI 结构化输出得到
 * <p>
 * 这个对象是「质检 → 条件边」之间的契约：
 * 条件边只看 {@link #isValid} 决定是继续构建还是退回重新生成；
 * 而 {@link #errors} 与 {@link #suggestions} 会被拼进下一次生成的提示词，
 * 让 AI 在<b>现有代码基础上修改</b>，而不是从头再生成一遍。
 *
 * @author <a>程序员haoy</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityResult implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 是否通过质检
     * <p>
     * true = 无严重语法错误、能正常运行和打包；false = 存在会导致跑不起来的问题。
     */
    private Boolean isValid;

    /**
     * 必须修复的错误列表
     */
    private List<String> errors;

    /**
     * 改进建议列表
     */
    private List<String> suggestions;

    /**
     * 是否「失败到需要返工重生成」
     * <p>
     * ⚠️ 判据刻意<b>不是</b>「{@code isValid == false}」那么简单，而是要求<b>同时</b>给出了错误项。原因：
     * 模型偶尔会返回 {@code isValid=false} 但 {@code errors} 为空 —— 这时没有任何可修复的信息，
     * 让代码生成节点带着「没有错误的修复提示词」再跑一轮纯属白烧 token。
     * <p>
     * 这个判据被<b>两处</b>共用，必须一致：条件边（决定要不要回退）和代码生成节点（决定用哪种提示词）。
     * 早期版本两边各写各的（一个只看 isValid、一个要求 errors 非空），结果就是「边判要返工、
     * 节点却认为没错误可修」，白跑一轮。现在统一收口到这里，杜绝再次漂移。
     *
     * @return true 表示存在可执行的修复项、值得回退重生成
     */
    public boolean needsRework() {
        return Boolean.FALSE.equals(isValid) && errors != null && !errors.isEmpty();
    }
}
