package com.haoy.aiapp.ai.tools;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 工具管理器
 * <p>
 * 统一持有所有 {@link BaseTool}，对外提供「按名字取工具」的能力，
 * 让流处理可以用一句 {@code toolManager.getTool(name)} 代替一长串 if-else。
 * <p>
 * 工具靠 <b>构造注入数组</b> 的方式自动收齐：每个工具类标了 {@code @Component}，
 * Spring 启动时会把它们一次性注进来，再在 {@link #initTools()} 里建好名字索引。
 * 这样「新增一个工具」只需要写一个类，工厂和流处理都不用改。
 * <p>
 * ⚠️ 两个必须守住的点：
 * <ol>
 *   <li><b>{@link #getAllTools()} 的返回值必须是数组，不能是集合。</b>
 *       {@code AiServices.tools(...)} 有两个重载：{@code tools(Object...)} 和
 *       {@code tools(Collection<Object>)}。传数组时 Java 会把它当作可变参数直接摊开，
 *       每个工具都被单独注册；而 {@code List<BaseTool>} 因为泛型不变性
 *       并不满足 {@code Collection<Object>}，会退化成 {@code tools(Object...)}
 *       的单个元素 —— LangChain4j 拿到的是一个 List 对象，扫不出任何 {@code @Tool} 方法，
 *       <b>结果是工具一个都没注册，但编译和启动都完全正常</b>，只在 AI 该调工具时表现为
 *       「它就是不肯用工具、非要自己瞎写」，极难排查。</li>
 *   <li><b>{@code getToolName()} 必须与 {@code @Tool} 方法名一致。</b>
 *       AI 请求里回传的工具名就是方法名，索引键写错了同样只会得到 null。</li>
 * </ol>
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
@Component
public class ToolManager {

    /**
     * 工具名 -> 工具实例
     */
    private final Map<String, BaseTool> toolMap = new HashMap<>();

    /**
     * 自动注入所有工具实现
     */
    @Resource
    private BaseTool[] tools;

    /**
     * 初始化工具映射
     */
    @PostConstruct
    public void initTools() {
        for (BaseTool tool : tools) {
            BaseTool existTool = toolMap.put(tool.getToolName(), tool);
            if (existTool != null) {
                // 重名会让后来者覆盖前者，必须显式报出来，否则又是一次「查不到工具」的悬案
                log.warn("工具名重复: {}，{} 覆盖了 {}",
                        tool.getToolName(), tool.getClass().getSimpleName(),
                        existTool.getClass().getSimpleName());
            }
            log.info("注册工具: {} -> {}", tool.getToolName(), tool.getDisplayName());
        }
        log.info("工具管理器初始化完成，共注册 {} 个工具", toolMap.size());
    }

    /**
     * 根据工具名称获取工具实例
     *
     * @param toolName 工具英文名称（即 {@code @Tool} 方法名）
     * @return 工具实例，未注册时返回 null
     */
    public BaseTool getTool(String toolName) {
        return toolMap.get(toolName);
    }

    /**
     * 获取已注册的全部工具
     * <p>
     * 返回值必须是数组（原因见类注释），直接交给 {@code AiServices.tools()} 使用。
     *
     * @return 工具实例数组
     */
    public BaseTool[] getAllTools() {
        return tools;
    }
}
