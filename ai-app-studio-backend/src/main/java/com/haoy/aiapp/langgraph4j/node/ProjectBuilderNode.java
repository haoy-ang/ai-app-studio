package com.haoy.aiapp.langgraph4j.node;

import cn.hutool.core.util.StrUtil;
import com.haoy.aiapp.core.builder.VueProjectBuilder;
import com.haoy.aiapp.langgraph4j.state.WorkflowContext;
import com.haoy.aiapp.model.enums.CodeGenTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;

import java.io.File;

import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 项目构建节点 —— VUE_PROJECT 要跑 npm 构建；HTML / 多文件已经落盘，直接用生成目录
 * <p>
 * 注意这是<b>串行版</b>的写法：用 if-else 在节点内部区分类型。
 * 到 P6 接入「条件边」之后，HTML / 多文件会在边上就被分流掉、压根不会进这个节点，
 * 那时这里的 if-else 就可以删掉。
 * <p>
 * ⚠️ 构建失败<b>不抛异常</b>：抛出去也会立刻被本节点的 {@code catch} 接住、退回原目录，
 * 等于白抛一场。
 * 这里直接记为日志 + 退回生成目录，语义一样但更好读。
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
public class ProjectBuilderNode {

    /**
     * 创建项目构建节点
     *
     * @param vueProjectBuilder Vue 项目构建器（npm install + npm run build）
     * @return 节点动作
     */
    public static AsyncNodeAction<MessagesState<String>> create(VueProjectBuilder vueProjectBuilder) {
        return node_async(state -> {
            WorkflowContext context = WorkflowContext.getContext(state);
            log.info("执行节点: 项目构建");

            String generatedCodeDir = context.getGeneratedCodeDir();
            CodeGenTypeEnum generationType = context.getGenerationType();
            String buildResultDir;

            if (generationType == CodeGenTypeEnum.VUE_PROJECT) {
                buildResultDir = generatedCodeDir;
                try {
                    boolean buildSuccess = vueProjectBuilder.buildProject(generatedCodeDir);
                    if (buildSuccess) {
                        // Vue 构建产物在 dist 目录，预览/部署要的是它
                        buildResultDir = generatedCodeDir + File.separator + "dist";
                        log.info("Vue 项目构建成功，dist 目录: {}", buildResultDir);
                    } else {
                        log.warn("Vue 项目构建失败，退回生成目录: {}", generatedCodeDir);
                    }
                } catch (Exception e) {
                    // 构建崩了也不能让整条工作流挂掉：至少源码还在，退回生成目录
                    log.error("Vue 项目构建异常，退回生成目录: {}", e.getMessage());
                    buildResultDir = generatedCodeDir;
                    context.setErrorMessage(StrUtil.blankToDefault(context.getErrorMessage(), e.getMessage()));
                }
            } else {
                // HTML / MULTI_FILE 在代码生成节点就已经把文件写好了，不需要额外构建
                buildResultDir = generatedCodeDir;
            }

            context.setCurrentStep("项目构建");
            context.setBuildResultDir(buildResultDir);
            log.info("项目构建节点完成，最终目录: {}", buildResultDir);
            return WorkflowContext.saveContext(context);
        });
    }
}
