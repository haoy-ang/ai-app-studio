package com.haoy.aiapp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 部署配置
 * <p>
 * 若把部署根目录写成 {@code System.getProperty("user.dir") + "/tmp/code_deploy"}，有两个隐患：
 * <ol>
 *   <li><b>依赖启动时的工作目录</b>：从 IDE 起（工作目录=模块目录）和从项目根起，算出来的路径不一样，
 *       写进去的文件和 Nginx 读的目录可能对不上；</li>
 *   <li><b>路径跟着源码目录走</b>：项目目录一改名（比如仓库重命名），Nginx 里硬编码的 root 立刻失效，
 *       访问部署地址直接 404。</li>
 * </ol>
 * 所以把「部署根目录」外置成配置项，并指向一个 <b>项目目录之外</b> 的固定绝对路径 ——
 * 后端与 Nginx 引用同一个稳定路径，项目随便改名/移动都不会再影响部署。
 * <p>
 * 运行时可用环境变量覆盖：{@code APP_DEPLOY_ROOT_DIR} / {@code APP_DEPLOY_HOST}。
 *
 * @author <a>程序员haoy</a>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "app.deploy")
public class DeployProperties {

    /**
     * 部署产物根目录
     * <p>
     * ⚠️ 必须与 Nginx 配置里的 {@code root} 完全一致（默认见 application.yml）。
     */
    private String rootDir = "E:/ai-app-studio-data/deploy";

    /**
     * 部署访问地址前缀（Nginx 对外监听的地址）
     */
    private String host = "http://localhost:8080";
}
