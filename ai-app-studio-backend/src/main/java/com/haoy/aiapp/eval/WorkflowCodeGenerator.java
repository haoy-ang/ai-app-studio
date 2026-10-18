package com.haoy.aiapp.eval;

import com.haoy.aiapp.constant.AppConstant;
import com.haoy.aiapp.langgraph4j.CodeGenWorkflow;
import com.haoy.aiapp.langgraph4j.state.WorkflowContext;
import com.haoy.aiapp.model.enums.CodeGenTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * 用真实的代码生成工作流作为评估的生成器
 * <p>
 * 它是 {@link CodeGenerator} 的生产实现 —— 唯一把「评估」和「AI 链路」连起来的地方。
 * 评估器本身不依赖 Spring，所以这个适配被单独放在一个类里，
 * 好处是「评估逻辑」和「怎么生成」可以分开演进。
 * <p>
 * <b>为什么用同步版 {@code executeWorkflow} 而不是 Flux 版？</b>
 * 评估要的是「跑完之后的产物在哪」，中间每一步的 SSE 事件没有价值 ——
 * 用 Flux 还得自己把流消费干净，反而多一层。
 * <p>
 * <b>为什么生成前要先删干净产物目录？</b>
 * 评估用的 appId 是固定的 {@code 900000 + 序号}，产物目录 {@code {type}_{appId}} 也就是固定路径。
 * 假如上一轮这条用例生成到一半失败了、目录里还躺着半套文件，这一轮检查器就会去检查
 * <b>上一轮的残留</b>，报告上表现为「这轮通过了」—— 比失败更糟，因为它骗人。
 * 所以每条用例开跑前先把三种类型对应的目录全删掉，代价是几次文件删除，
 * 换来的是「报告里的通过一定是这一轮生成的」这个前提。
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
@Component
public class WorkflowCodeGenerator implements CodeGenerator {

    private final CodeGenWorkflow codeGenWorkflow;

    public WorkflowCodeGenerator(@Qualifier("codeGenWorkflow") CodeGenWorkflow codeGenWorkflow) {
        this.codeGenWorkflow = codeGenWorkflow;
    }

    @Override
    public Generated generate(EvalCase evalCase, Long appId) {
        cleanArtifacts(appId);
        try {
            WorkflowContext context = codeGenWorkflow.executeWorkflow(evalCase.prompt(), appId);
            if (context == null) {
                return Generated.failed("工作流没有返回上下文");
            }
            if (context.getGenerationType() == null) {
                return Generated.failed("工作流没有判定生成类型");
            }
            if (context.getGeneratedCodeDir() == null) {
                return Generated.failed("工作流没有产出代码目录");
            }
            // 降级信息必须往上传：路由被 429 打断时产物类型会是兜底的 HTML，
            // 报告里如果只看到「类型不对」而看不到「为什么不对」，就会误判成模型能力问题
            boolean degraded = Boolean.TRUE.equals(context.getRoutingDegraded());
            if (degraded) {
                log.warn("用例 {} 的路由发生了降级: {}", evalCase.id(), context.getRoutingDegradeReason());
            }
            return Generated.ok(context.getGenerationType(), Path.of(context.getGeneratedCodeDir()),
                    degraded, context.getRoutingDegradeReason());
        } catch (Exception e) {
            // 不往上抛：一条用例炸了不该中断整轮，另外 23 条的钱已经花了
            log.warn("用例 {} 生成失败: {}", evalCase.id(), e.getMessage());
            return Generated.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * 删掉该 appId 名下所有可能的产物目录
     * <p>
     * 生成类型要等 AI 路由完才知道，所以 HTML / 多文件 / Vue 三种目录全删 ——
     * 反正评估用的 appId 是独占的，不会误伤真实应用。
     * <p>
     * 删除失败只记日志不中断：删不掉顶多是「可能读到残留」，
     * 而拿这个理由让一条用例整轮失败，代价更大。
     *
     * @param appId 评估专用 appId
     */
    private void cleanArtifacts(Long appId) {
        if (appId == null) {
            return;
        }
        Path root = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR);
        // 三个枚举的 value 正好就是目录前缀（html / multi_file / vue_project），
        // 所以这一圈既覆盖单文件、多文件，也覆盖 Vue 工程目录
        for (CodeGenTypeEnum type : CodeGenTypeEnum.values()) {
            deleteRecursively(root.resolve(type.getValue() + "_" + appId));
        }
    }

    /**
     * 递归删除目录；不存在时直接返回
     * <p>
     * 没用 {@code FileUtil.del} 是因为这里要「从叶子往上删」，顺序确定才删得干净。
     * try-with-resources 包住 {@code Files.walk} 是必须的 —— 它持有目录句柄，
     * 不关的话 Windows 上会遇到「文件被占用，删不掉」。
     */
    private void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("清理评估产物失败，跳过 {}: {}", path, e.getMessage());
                }
            });
            log.info("已清理旧产物目录: {}", dir);
        } catch (IOException | UncheckedIOException e) {
            log.warn("清理评估产物目录失败，跳过 {}: {}", dir, e.getMessage());
        }
    }
}
