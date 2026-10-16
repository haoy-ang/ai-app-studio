package com.haoy.aiapp.ai.tools;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import com.haoy.aiapp.constant.AppConstant;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 工具基类
 * <p>
 * 「可视化修改」功能给 AI 挂了一整套文件工具（读文件、读目录、改文件、删文件、写文件），
 * 它们的共同点是：<b>都要把 AI 给的相对路径落到当前应用的项目目录里</b>，
 * 都要把异常转成一句「人话」回给 AI。这些逻辑放在基类里，子类只写自己的核心动作。
 * <p>
 * 设计上这是「策略模式」：每个工具是一个策略，对外提供两件事 ——
 * <ul>
 *   <li>{@link #generateToolRequestResponse()}：告诉用户「AI 正在用哪个工具」</li>
 *   <li>{@link #generateToolExecutedResult(JSONObject)}：告诉用户「这个工具具体干了什么」</li>
 * </ul>
 * 有了这层抽象，流处理里就不用写一长串 {@code if (toolName.equals("writeFile")) ... else if ...}，
 * 而是统一从 {@link ToolManager} 取工具再调用，新增工具不需要改流处理代码。
 * <p>
 * ⚠️ 注意：基类里的方法<b>没有</b> {@code @Tool} 注解，LangChain4j 只会扫描子类里带 {@code @Tool} 的方法，
 * 所以基类的这些辅助方法不会被当成工具暴露给 AI。
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
public abstract class BaseTool {

    /**
     * 获取工具的英文名称，必须与 {@code @Tool} 方法名一致
     * <p>
     * 因为流处理里拿到的工具名来自 AI 的调用请求（就是方法名），
     * 不一致会导致 {@link ToolManager#getTool(String)} 查不到工具、输出变成空白。
     *
     * @return 工具英文名称
     */
    public abstract String getToolName();

    /**
     * 获取工具的中文显示名称，用于给用户看的提示文案
     *
     * @return 工具中文名称
     */
    public abstract String getDisplayName();

    /**
     * 生成「AI 开始调用某个工具」时的提示文案（显示给用户）
     * <p>
     * 理想情况下这句话应在 AI 刚发起工具调用（TOOL_REQUEST 事件）时就提示，
     * 但 langchain4j 1.1.0 的 {@code TokenStream} 没有暴露该事件，
     * 所以本项目改在 TOOL_EXECUTED（工具执行完成）时补上这句提示，
     * 语义略有滞后，但用户看到的文案是一致的。
     *
     * @return 工具请求显示内容
     */
    public String generateToolRequestResponse() {
        return String.format("\n\n[选择工具] %s\n\n", getDisplayName());
    }

    /**
     * 生成工具执行结果的展示格式（同时用于前端展示和落库的对话历史）
     *
     * @param arguments 工具调用的完整参数
     * @return 格式化后的工具执行结果
     */
    public abstract String generateToolExecutedResult(JSONObject arguments);

    /**
     * 获取当前应用的项目根目录
     *
     * @param appId 应用 ID（由 LangChain4j 通过 {@code @ToolMemoryId} 自动注入）
     * @return 项目根目录
     */
    protected Path getProjectRoot(Long appId) {
        if (appId == null) {
            // appId 缺失说明 @ToolMemoryId 没生效，此时再往下走会写出 vue_project_null 目录
            throw new IllegalArgumentException("缺少 appId，无法定位应用目录");
        }
        return Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR,
                AppConstant.VUE_PROJECT_DIR_PREFIX + appId).normalize();
    }

    /**
     * 把 AI 给的相对路径解析到当前应用的项目目录内，并校验没有越界
     * <p>
     * ⚠️ 这是本类<b>最不能省</b>的一段。图省事直接拼接路径、不做校验是这样的：
     * <pre>
     * Path path = Paths.get(relativeFilePath);
     * if (!path.isAbsolute()) { path = projectRoot.resolve(relativeFilePath); }
     * </pre>
     * 两个问题：
     * <ol>
     *   <li><b>绝对路径直接放行</b> —— AI 只要传 {@code C:\Users\xxx\.ssh\id_rsa}，
     *       读文件工具就会把它读出来、删文件工具就能把它删掉；</li>
     *   <li><b>没防路径穿越</b> —— {@code ../../../Users/xxx/Desktop} 这种相对路径
     *       resolve 之后同样会跑出项目目录。</li>
     * </ol>
     * 正确做法是 normalize 之后判断是否仍以项目根目录开头（{@link Path#startsWith(Path)}）。
     * 注意必须先 normalize：{@code projectRoot.resolve("../a").startsWith(projectRoot)} 为 true，
     * normalize 成 {@code .../a} 之后才判断得出来。
     * <p>
     * 传空路径时返回项目根目录本身（读目录工具用「空 = 整个项目」的语义）。
     *
     * @param appId        应用 ID
     * @param relativePath AI 给的相对路径
     * @return 校验通过的真实路径
     * @throws IllegalArgumentException 路径为空之外还越界时抛出，由 {@link #safeExecute} 转成给 AI 的提示
     */
    protected Path resolveAppPath(Long appId, String relativePath) {
        Path projectRoot = getProjectRoot(appId);
        if (StrUtil.isBlank(relativePath)) {
            return projectRoot;
        }
        Path target = projectRoot.resolve(relativePath.trim()).normalize();
        if (!target.startsWith(projectRoot)) {
            throw new IllegalArgumentException(
                    "非法路径，禁止访问项目目录之外的位置: " + relativePath);
        }
        return target;
    }

    /**
     * 从工具参数里安全地取一个字符串
     * <p>
     * 参数缺失时返回空串而不是 null。{@code String.format} 遇到 null 会原样输出 "null"，
     * 用户看到的就是「[工具调用] 写入文件 null」这种莫名其妙的文案；
     * AI 偶尔会给某个参数传空字符串或不传，所以统一在这里兜住。
     *
     * @param arguments 工具调用参数
     * @param key       参数名
     * @return 参数值，缺失时为空串
     */
    protected String getStrOrEmpty(JSONObject arguments, String key) {
        return StrUtil.blankToDefault(arguments.getStr(key), "");
    }

    /**
     * 工具的核心动作
     * <p>
     * 这里刻意不用 {@code java.util.function.Supplier}：文件操作
     * （{@code Files.readString} / {@code write} / {@code delete}）抛的都是受检的
     * {@code IOException}，而 {@code Supplier.get()} 不允许抛受检异常 ——
     * 用它就得逼着五个工具各自再写一遍 try/catch，正好把这层抽象要消掉的东西又加回来。
     * <p>
     * 抛受检异常这件事由 {@link #safeExecute} 统一兜住，工具实现里可以放心直接调用 JDK 的文件 API。
     */
    @FunctionalInterface
    protected interface ToolAction {

        /**
         * 执行工具动作
         *
         * @return 返回给 AI 的文本
         * @throws Exception 执行过程中的任何异常
         */
        String run() throws Exception;
    }

    /**
     * 统一的异常兜底：把异常转成一句可以直接回给 AI 的文本
     * <p>
     * 为什么不让子类各自 try/catch：五个工具重复五遍一样的代码没有意义，
     * 而且很容易漏掉某个分支。这里统一处理有两层考虑：
     * <ol>
     *   <li><b>参数非法不记 error</b> —— AI 传错路径属于「它自己会改」的情况，
     *       返回错误描述让它重试即可，记 warn 就够，不然日志会被无意义的堆栈刷满。</li>
     *   <li><b>工具方法不能抛异常</b> —— 工具是被 LangChain4j 反射调用的，
     *       抛出去会中断整条 AI 流式响应、用户侧表现为「生成到一半断了」，
     *       所以这里必须兜住，把失败也变成一次正常的工具返回。</li>
     * </ol>
     *
     * @param action 工具的核心动作
     * @return 动作的返回内容，或异常信息
     */
    protected String safeExecute(ToolAction action) {
        try {
            return action.run();
        } catch (IllegalArgumentException e) {
            log.warn("[{}] 参数校验失败: {}", getDisplayName(), e.getMessage());
            return "错误：" + e.getMessage();
        } catch (Exception e) {
            String errorMessage = getDisplayName() + "失败: " + e.getMessage();
            log.error(errorMessage, e);
            return errorMessage;
        }
    }
}
