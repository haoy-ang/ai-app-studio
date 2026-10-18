package com.haoy.aiapp.eval.inspect;

import java.nio.file.Path;

/**
 * 一个产物检查项
 * <p>
 * <b>为什么做成接口而不是一堆 if-else？</b>因为断言会持续增加（每踩一个坑就加一条）。
 * 做成接口后，加断言只需新写一个类并注册到 {@link ArtifactInspector}，不用动已有逻辑，
 * 也不会把一条新断言写进别人的分支里。
 * <p>
 * <b>为什么每个检查项自己判「跳过」？</b>因为「这条检查该不该跑」往往依赖前置条件
 * （比如文件都不存在时，标签配平没法判断）。让检查项自己决定跳过，比在分发器里写一堆前置判断更内聚。
 *
 * @author <a>程序员haoy</a>
 */
public interface ArtifactCheck {

    /**
     * 检查项名称 —— 报告里用它定位，必须稳定（改名等于让历史报告失去可比性）
     *
     * @return 名称，如 {@code html-tags-balanced}
     */
    String name();

    /**
     * 执行检查
     *
     * @param root    产物根目录（{@code {CODE_OUTPUT_ROOT_DIR}/{type}_{appId}}）
     * @param options 检查选项（是否跑构建等）
     * @return 检查结果，不允许返回 null
     */
    CheckResult inspect(Path root, ArtifactInspector.Options options);
}
