package com.haoy.aiapp.ai.tools;

import cn.hutool.json.JSONObject;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 退出工具
 * <p>
 * 给 AI 一个「主动收工」的开关。Vue 工程模式是工具调用型生成，模型每写一个文件就要再问一次
 * 「还有没有事要做」，有时候它会误判成「还没完」而一轮轮地重写同一批文件（第 9 节实测过
 * 连续 19 分钟调了 196 次 writeFile）。有了这个工具，AI 在任务完成时可以显式结束循环，
 * 而不是被动等 {@code maxSequentialToolsInvocations} 把轮次耗光。
 * <p>
 * ⚠️ 两点必须清楚：
 * <ol>
 *   <li>它只是「多给模型一个选择」，<b>不是</b>死循环的兜底。真正确定性生效的刹车是
 *       {@link FileWriteTool} 的幂等跳过（目标文件内容和要写的完全一致就不落盘、
 *       并回一句「已跳过，请立即结束回复」）。退出工具解决的是「模型想停但没有工具可停」，
 *       不是「模型不知道该停」。</li>
 *   <li>每多一个工具都会增加提示词体积和模型的决策成本，所以工具集不是越多越好 ——
 *       如果实测发现它反而让模型更容易走神（社区有人反馈加了退出类工具后出现循环），
 *       直接删掉这个类即可，工厂那边会自动少注册一个工具，不需要改任何其它代码。</li>
 * </ol>
 * 另外这里<b>没有</b> {@code @ToolMemoryId} 参数：退出动作跟具体是哪个应用无关。
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
@Component
public class ExitTool extends BaseTool {

    /**
     * 退出工具调用
     * <p>
     * 返回值是给 AI 看的话：明确告诉它「可以输出最终结果了」，
     * 因为有些模型会把这个工具当成又一次「查询」，拿到结果后继续调别的工具。
     *
     * @return 退出确认信息
     */
    @Tool("当任务已完成或无需继续调用工具时，使用此工具退出操作，防止循环")
    public String exit() {
        log.info("AI 请求退出工具调用");
        return "不要继续调用工具，可以输出最终结果了";
    }

    @Override
    public String getToolName() {
        return "exit";
    }

    @Override
    public String getDisplayName() {
        return "退出工具调用";
    }

    /**
     * 工具执行结果的展示格式
     * <p>
     * 不显示参数（它没有参数），只给一个收尾标记，让用户在流式输出里能看出
     * 「AI 决定收工了」这件事发生了。这段文案同样会进对话历史。
     */
    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        return "\n\n[执行结束]\n\n";
    }
}
