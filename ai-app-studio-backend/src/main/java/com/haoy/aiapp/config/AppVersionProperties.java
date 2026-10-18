package com.haoy.aiapp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 应用版本（快照/回滚）配置
 * <p>
 * <b>为什么快照根目录也要外置：</b>和部署目录同一个理由 —— 快照是「用户资产」，
 * 不能跟着 {@code user.dir} 漂。项目目录改名、从 IDE 启动、打成 jar 跑，
 * 工作目录都可能不一样，跟着它走会导致昨天还能回滚的版本今天找不到。
 * <p>
 * 运行时可用环境变量覆盖：{@code APP_VERSION_ROOT_DIR}。
 *
 * @author <a>程序员haoy</a>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "app.version")
public class AppVersionProperties {

    /**
     * 是否启用版本快照
     * <p>
     * 关掉之后「生成」不再打快照，接口仍可查询历史版本。
     * 评估（L2 回归）会大量生成一次性 appId，没必要给它们留快照。
     */
    private boolean enabled = true;

    /**
     * 快照根目录（项目目录之外的固定绝对路径）
     */
    private String rootDir = "E:/ai-app-studio-data/versions";

    /**
     * 每个应用最多保留多少个版本（含当前版本）
     * <p>
     * 超出的从最老的开始清理：<b>先删目录，再物理删记录</b>。
     * 之所以敢物理删：版本号计数走 Redis 自增，不依赖「表里最大号」，
     * 删掉的号不会被复用，也就不会和 UK(appId, versionNo) 打架。
     */
    private int maxKeep = 10;

    /**
     * 单个快照体积上限（字节），默认 32MB
     * <p>
     * 超限直接跳过本次快照并打 warn —— 宁可少一个版本，也不要把磁盘写爆。
     * Vue 工程的 node_modules / dist 已在存储层排除，正常项目远达不到这个量级。
     */
    private long maxSizeBytes = 32L * 1024 * 1024;
}
