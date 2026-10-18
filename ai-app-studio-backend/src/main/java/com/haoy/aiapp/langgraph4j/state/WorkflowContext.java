package com.haoy.aiapp.langgraph4j.state;

import com.haoy.aiapp.exception.BusinessException;
import com.haoy.aiapp.exception.ErrorCode;
import com.haoy.aiapp.langgraph4j.model.ImageCollectionPlan;
import com.haoy.aiapp.langgraph4j.model.ImageResource;
import com.haoy.aiapp.langgraph4j.model.QualityResult;
import com.haoy.aiapp.model.enums.CodeGenTypeEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bsc.langgraph4j.prebuilt.MessagesState;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 工作流上下文 —— 所有节点共享的状态载体
 * <p>
 * LangGraph4j 官方推荐用内置的 Schema + Channel.Reducer 来维护状态，但那套语法
 * （{@code Map<String, Channel.Reducer>}、各种 Reducer 合并策略）比较绕，只有在需要
 * 用框架的持久化能力时才划算。本项目不需要，所以用一个普通 POJO 统一维护，
 * 想怎么读写就怎么读写。
 * <p>
 * 为了和 LangGraph4j 要求的 {@code AgentState} 兼容，做法是把 Context 以一个 key-value
 * 塞进 {@code MessagesState} 里（键是 {@link #WORKFLOW_CONTEXT_KEY}），
 * 各节点通过 {@link #getContext(MessagesState)} 取出、改完再用 {@link #saveContext(WorkflowContext)} 存回。
 * <p>
 * ⚠️ <b>存进状态里的是 JSON 字符串，不是对象本身</b> —— 原因写在 {@link #saveContext} 的注释里，
 * 一句话概括：框架会序列化状态，而对象一旦跨序列化 / 跨类加载器还原，就会变成「两个不同加载器的
 * 同名类」，直接强转会抛 {@code ClassCastException}。存 JSON 字符串（JDK 类型）能彻底绕开这件事。
 *
 * @author <a>程序员haoy</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowContext implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * WorkflowContext 在 MessagesState 中的存储 key
     */
    public static final String WORKFLOW_CONTEXT_KEY = "workflowContext";

    // ==================== 基础信息 ====================

    /**
     * 当前执行步骤 —— 只用于日志和 SSE 进度推送，不参与业务逻辑
     */
    private String currentStep;

    /**
     * 用户原始输入的提示词
     */
    private String originalPrompt;

    /**
     * 应用 ID
     * <p>
     * 默认 0L 表示「脱离业务流程单独跑工作流」（例如单元测试里就是这么用的）。
     * 真正接入业务时由调用方传入真实 appId，否则生成的文件会落到
     * {@code {@literal {codeType}}_0} 这种目录里，和真实应用对不上。
     */
    @Builder.Default
    private Long appId = 0L;

    // ==================== 图片收集 ====================

    /**
     * 图片资源字符串 —— AI 以文本形式返回的图片信息（工具调用模式下用）
     */
    private String imageListStr;

    /**
     * 图片资源列表 —— 结构化收集方式得到的图片（并发收集模式下用）
     */
    private List<ImageResource> imageList;

    /**
     * 图片收集计划 —— 由「图片计划」节点生成，供各并发分支消费
     */
    private ImageCollectionPlan imageCollectionPlan;

    /**
     * 并发图片收集的中间结果字段
     * <p>
     * 四个并发分支各自写自己的字段、互不干扰，最后由「图片聚合」节点汇总进 {@link #imageList}。
     * 这样设计是为了避免并发写同一个 List 造成覆盖。
     */
    private List<ImageResource> contentImages;

    private List<ImageResource> illustrations;

    private List<ImageResource> diagrams;

    private List<ImageResource> logos;

    // ==================== 生成流程 ====================

    /**
     * 增强后的提示词（原始提示词 + 可用素材资源清单）
     */
    private String enhancedPrompt;

    /**
     * 代码生成类型（由智能路由节点决定）
     */
    private CodeGenTypeEnum generationType;

    /**
     * 路由是否降级了
     * <p>
     * <b>这个字段存在的理由，是把「路由判断错了」和「路由根本没跑成」分开。</b>
     * 两者都会让产物类型不符合需求，但原因完全不同：
     * <ul>
     *   <li>判断错 —— 是提示词 / 模型能力问题，该改提示词；</li>
     *   <li>降级 —— 是上游 429 / 超时，模型压根没机会判断，改提示词毫无意义。</li>
     * </ul>
     * 混在一起统计，会让「路由准确率」这个指标在限流的日子里毫无参考价值 ——
     * 真实测出来的就是这件事：三条用例里两条被 429 打成 HTML，看上去像模型很差。
     */
    private Boolean routingDegraded;

    /**
     * 降级原因（上游异常的一句话摘要），只在 {@link #routingDegraded} 为 true 时有值
     */
    private String routingDegradeReason;

    /**
     * 生成的代码目录
     */
    private String generatedCodeDir;

    /**
     * 构建成功的目录（Vue 工程是 dist 目录，其他类型与生成目录相同）
     */
    private String buildResultDir;

    /**
     * 质量检查结果 —— 「代码质检」节点写入，「质检条件边」读取
     */
    private QualityResult qualityResult;

    /**
     * 代码生成已经执行的次数（含首次）。
     * <p>
     * 专门给「质检失败回退」那条循环边用的刹车：若只写「质检不通过就回代码生成节点」、
     * 却没有次数上限，只要 AI 一直判不通过，工作流就会无限转下去
     * （本项目已经踩过「模型犯病会一直重写文件、SSE 永不结束」的坑，这类循环尤其危险）。
     * 有了这个计数，条件边就能在超过上限时主动放弃修复、放行到后续节点。
     */
    @Builder.Default
    private Integer codeGenerationCount = 0;

    /**
     * 错误信息
     */
    private String errorMessage;

    // ==================== 上下文操作方法 ====================

    /**
     * 状态序列化用的 ObjectMapper。
     * <p>
     * 单独一个实例、不注入 Spring 的那个，原因有二：这是个静态工具方法没法注入；
     * 而且 Spring 那个配置了「Long 转 String」（见 {@code config/JsonConfig}），
     * 那是给前端防精度丢失用的，这里把 19 位 appId 当数字存回来反而更直接。
     * <p>
     * ⚠️ 用 Jackson 而不是 Hutool 的 {@code JSONUtil}：Hutool 能把枚举序列化成名字，
     * 但反序列化时会抛 {@code ConvertException: Can not convert VUE_PROJECT to CodeGenTypeEnum}，
     * 所以 {@code generationType} 这种枚举字段直接还原不回来（实测踩过）。
     * Jackson 对枚举 / 泛型 List / 嵌套 record 都是原生支持，本项目里也已有用例验证。
     */
    private static final ObjectMapper STATE_OBJECT_MAPPER = new ObjectMapper();

    /**
     * 从 MessagesState 中获取 WorkflowContext
     * <p>
     * 三种情况都要接住，顺序不能乱：
     * <ol>
     *   <li>状态里是 JSON 字符串 —— <b>正常路径</b>（{@link #saveContext} 就是这么存的），解析成对象返回；</li>
     *   <li>状态里直接就是本加载器的 Context 对象 —— 兼容手写 state 的场景（测试、调试）；</li>
     *   <li>状态里是<b>另一个类加载器</b>加载的 Context 对象 —— 见 {@link #saveContext} 的注释。
     *       此时 {@code instanceof} 会是 false（同名的两个类不是同一个类），
     *       所以先把它转成 JSON 再解析回来，落到本加载器的类型上。</li>
     * </ol>
     *
     * @param state 当前工作流状态
     * @return 上下文对象；若尚未初始化则返回 null，调用方需要判空
     */
    public static WorkflowContext getContext(MessagesState<String> state) {
        Object raw = state.data().get(WORKFLOW_CONTEXT_KEY);
        if (raw == null) {
            return null;
        }
        if (raw instanceof WorkflowContext context) {
            return context;
        }
        try {
            String json = (raw instanceof CharSequence text) ? text.toString()
                    : STATE_OBJECT_MAPPER.writeValueAsString(raw);
            return STATE_OBJECT_MAPPER.readValue(json, WorkflowContext.class);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "工作流状态反序列化失败: " + e.getMessage());
        }
    }

    /**
     * 将 WorkflowContext 保存回 MessagesState
     * <p>
     * 节点的返回值就是这个 Map，框架会据此更新状态，所以每个节点最后必须 return 一次。
     * <p>
     * ⚠️ <b>这里存的是 JSON 字符串，而不是 Context 对象本身</b>。踩过的坑，值得记一笔：
     * LangGraph4j 在节点之间传递状态时会做一次 Java 序列化往返（框架自带
     * {@code ObjectStreamStateSerializer}）。而反序列化时类是从「最近的用户自定义类加载器」上找的 ——
     * 开着 Spring Boot DevTools 时，项目自己的类由 {@code RestartClassLoader} 加载，
     * 反序列化回来的对象却挂在基础加载器上，于是节点里
     * {@code (WorkflowContext) raw} 会直接抛：
     * <pre>
     *   ClassCastException: WorkflowContext cannot be cast to WorkflowContext
     *     (loader 'app'  vs  RestartClassLoader)
     * </pre>
     * 报错信息里同一个类名出现两次，看着非常费解；而且<b>单元测试永远测不出来</b>
     * （{@code @SpringBootTest} 不走 RestartClassLoader），只有真的把后端跑起来打接口才会炸。
     * <p>
     * 存 JSON 字符串之后，状态里只留 JDK 类型，序列化往返怎么折腾都不会产生「两个同名类」，
     * 从根上绕开了这个问题，而且不依赖任何启动参数（DevTools 那个
     * {@code spring.devtools.restart.enabled=false} 只停止监视文件变化，<b>并不会</b>阻止
     * RestartClassLoader 被创建，靠它绕不过去 —— 实测验证过）。
     *
     * @param context 要保存的上下文
     * @return 供节点返回的状态更新 Map
     */
    public static Map<String, Object> saveContext(WorkflowContext context) {
        try {
            return Map.of(WORKFLOW_CONTEXT_KEY, STATE_OBJECT_MAPPER.writeValueAsString(context));
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "工作流状态序列化失败: " + e.getMessage());
        }
    }
}
