package com.haoy.aiapp.eval;

import com.haoy.aiapp.model.enums.CodeGenTypeEnum;

import java.nio.file.Path;

/**
 * 把「一条需求」变成「一个产物目录」的能力
 * <p>
 * <b>为什么要抽这一层，而不是让 {@link EvalRunner} 直接调工作流？</b>
 * 两个原因：
 * <ol>
 *   <li><b>评估的流程逻辑必须能免费测试。</b>限速、超时、报告 diff、基线对比这些都有分支，
 *       但它们跟「AI 今天写得好不好」无关。有了这层接口，用假实现就能把整条流水线跑通、
 *       断言报告内容，一次 AI 都不用调。</li>
 *   <li><b>生成方式会变。</b>现在走 {@code CodeGenWorkflow}，以后可能是「先检索相似应用再改」、
 *       也可能是「多模型投票」。换实现不该动评估器。</li>
 * </ol>
 * <p>
 * 生产实现见 {@code WorkflowCodeGenerator}（依赖 Spring 容器）；测试实现直接返回预置目录。
 *
 * @author <a>程序员haoy</a>
 */
@FunctionalInterface
public interface CodeGenerator {

    /**
     * 执行一次生成
     *
     * @param evalCase 用例（提供需求原文）
     * @param appId    本次评估专用的应用 ID，决定产物落哪个目录
     * @return 生成结果；失败时 {@link Generated#error()} 非空而不是抛异常
     */
    Generated generate(EvalCase evalCase, Long appId);

    /**
     * 一次生成的结果
     * <p>
     * <b>为什么失败用字段而不是抛异常？</b>因为评估要的是「这一轮 24 条里几条成功」，
     * 一条炸了不该让整轮中断 —— 那会让你丢掉另外 23 条已经花掉的钱和时间。
     *
     * @param actualType          AI 实际判定的生成类型；路由失败时为 null
     * @param artifactDir         产物根目录；失败时为 null
     * @param error               失败原因；成功时为 null
     * @param routingDegraded     路由是否降级（主模型失败、由备用模型或默认值接手）
     * @param routingDegradeReason 降级原因；未降级时为 null
     */
    record Generated(CodeGenTypeEnum actualType,
                     Path artifactDir,
                     String error,
                     boolean routingDegraded,
                     String routingDegradeReason) {

        /**
         * 生成成功且拿到了产物目录
         */
        public boolean succeeded() {
            return error == null && artifactDir != null && actualType != null;
        }

        public static Generated ok(CodeGenTypeEnum actualType, Path artifactDir) {
            return new Generated(actualType, artifactDir, null, false, null);
        }

        public static Generated ok(CodeGenTypeEnum actualType, Path artifactDir,
                                   boolean routingDegraded, String routingDegradeReason) {
            return new Generated(actualType, artifactDir, null, routingDegraded, routingDegradeReason);
        }

        public static Generated failed(String error) {
            return new Generated(null, null, error, false, null);
        }
    }
}
