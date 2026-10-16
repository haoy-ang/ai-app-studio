package com.haoy.aiapp.core.builder;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.concurrent.TimeUnit;

/**
 * Vue 项目构建器
 * <p>
 * AI 写出的是「源代码」，不是能直接访问的网站：必须
 * {@code npm install} 装依赖、{@code npm run build} 打包出 {@code dist} 目录，
 * 浏览和部署访问的都是这个 dist 目录。
 * <p>
 * 构建很慢（首次装依赖可能几分钟），调用方需要明确知道自己是在<b>同步</b>等它 ——
 * 生成链路选择同步构建（构建完预览才就绪），所以这里不再提供「异步 fire-and-forget」入口。
 * <p>
 * 历史沿革：早期有一个 {@code buildProjectAsync}（虚拟线程后台跑），在改为同步构建后
 * 它成了死代码，已删除。工作流的 {@code ProjectBuilderNode} 用的是下面的同步
 * {@link #buildProject(String)}，两者不冲突。
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
@Component
public class VueProjectBuilder {

    /** npm install 超时时间（秒）：首次装依赖较慢，给足 5 分钟 */
    private static final int NPM_INSTALL_TIMEOUT_SECONDS = 300;

    /** npm run build 超时时间（秒） */
    private static final int NPM_BUILD_TIMEOUT_SECONDS = 180;

    /**
     * 构建 Vue 项目（同步）
     * <p>
     * 注意：这个方法**不抛异常**，只返回成功与否。
     * 因为它是被主流程顺带调用的，抛异常会波及“代码生成”这条主干。
     *
     * @param projectPath 项目根目录路径
     * @return 是否构建成功
     */
    public boolean buildProject(String projectPath) {
        File projectDir = new File(projectPath);
        if (!projectDir.exists() || !projectDir.isDirectory()) {
            log.error("项目目录不存在: {}", projectPath);
            return false;
        }
        // 检查 package.json 是否存在（AI 没生成这个文件就没法构建）
        File packageJson = new File(projectDir, "package.json");
        if (!packageJson.exists()) {
            log.error("package.json 文件不存在: {}", packageJson.getAbsolutePath());
            return false;
        }
        log.info("开始构建 Vue 项目: {}", projectPath);
        // 1. 安装依赖
        if (!executeNpmInstall(projectDir)) {
            log.error("npm install 执行失败");
            return false;
        }
        // 2. 打包构建
        if (!executeNpmBuild(projectDir)) {
            log.error("npm run build 执行失败");
            return false;
        }
        // 3. 校验产物
        File distDir = new File(projectDir, "dist");
        if (!distDir.exists()) {
            log.error("构建完成但 dist 目录未生成: {}", distDir.getAbsolutePath());
            return false;
        }
        log.info("Vue 项目构建成功，dist 目录: {}", distDir.getAbsolutePath());
        return true;
    }

    /**
     * 执行 npm install
     */
    private boolean executeNpmInstall(File projectDir) {
        log.info("执行 npm install...");
        String command = buildCommand("npm") + " install";
        return executeCommand(projectDir, command, NPM_INSTALL_TIMEOUT_SECONDS, "npm-install.log");
    }

    /**
     * 执行 npm run build
     */
    private boolean executeNpmBuild(File projectDir) {
        log.info("执行 npm run build...");
        String command = buildCommand("npm") + " run build";
        return executeCommand(projectDir, command, NPM_BUILD_TIMEOUT_SECONDS, "npm-build.log");
    }

    /**
     * 执行命令并等待结束
     * <p>
     * 与直接用 Hutool {@code RuntimeUtil.exec} + {@code waitFor} 相比，这里多做了一件事：
     * 把子进程输出**重定向到日志文件**。npm 的输出量很大，如果不消费 stdout，
     * 管道缓冲区写满后子进程会卡在写日志上、永远不退出，waitFor 只能等到超时再强杀，
     * 表现为「明明装完了却报失败」。重定向到文件同时还留下了排查线索。
     * <p>
     * npm 命令通过 PATH 查找；如果本地环境 PATH 里没有 npm，
     * 把 {@code buildCommand} 的返回值改成 npm 的绝对路径即可。
     *
     * @param workingDir     工作目录（项目根目录）
     * @param command        命令字符串
     * @param timeoutSeconds 超时时间（秒）
     * @param logFileName    子进程输出落盘的文件名（相对项目目录）
     * @return 是否执行成功（退出码为 0）
     */
    private boolean executeCommand(File workingDir, String command, int timeoutSeconds, String logFileName) {
        File logFile = new File(workingDir, logFileName);
        try {
            log.info("在目录 {} 中执行命令: {}", workingDir.getAbsolutePath(), command);
            ProcessBuilder processBuilder = new ProcessBuilder(command.split("\\s+"));
            processBuilder.directory(workingDir);
            // 错误流合并进标准输出，避免两条管道都要单独消费
            processBuilder.redirectErrorStream(true);
            processBuilder.redirectOutput(ProcessBuilder.Redirect.to(logFile));
            Process process = processBuilder.start();
            // 等待进程完成，设置超时
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                log.error("命令执行超时（{} 秒），强制终止进程: {}", timeoutSeconds, command);
                process.destroyForcibly();
                return false;
            }
            int exitCode = process.exitValue();
            if (exitCode == 0) {
                log.info("命令执行成功: {}", command);
                return true;
            }
            log.error("命令执行失败，退出码: {}，详情见: {}", exitCode, logFile.getAbsolutePath());
            return false;
        } catch (InterruptedException e) {
            // 恢复中断标记，不要吞掉
            Thread.currentThread().interrupt();
            log.error("命令执行被中断: {}", command, e);
            return false;
        } catch (Exception e) {
            log.error("执行命令失败: {}, 错误信息: {}, 详情见: {}",
                    command, e.getMessage(), logFile.getAbsolutePath(), e);
            return false;
        }
    }

    /**
     * 判断是否 Windows 系统
     */
    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("windows");
    }

    /**
     * 根据操作系统构造命令名（Windows 下 npm / npx 这类命令必须带 .cmd 后缀）
     */
    private String buildCommand(String baseCommand) {
        if (isWindows()) {
            return baseCommand + ".cmd";
        }
        return baseCommand;
    }
}
