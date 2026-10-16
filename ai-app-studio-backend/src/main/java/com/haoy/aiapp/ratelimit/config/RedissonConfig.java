package com.haoy.aiapp.ratelimit.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 客户端配置
 * <p>
 * 只给「限流」用。项目里其实已经有两条 Redis 连接了：
 * <ul>
 *   <li>Spring Session 的 {@code spring-session-data-redis}（存登录态）</li>
 *   <li>langchain4j 的 community-redis（存聊天记忆）</li>
 * </ul>
 * 它们各自管各自的，Redisson 再单独建一条也没问题 —— 限流器是 Redis 里的一个特殊对象，
 * 用现成的 {@code RedisTemplate} 反而写不出来，所以引入 Redisson 是合理的选择。
 * <p>
 * ⚠️ 与默认写法的一点偏差：直接用 {@code @Value("${spring.data.redis.xxx}")} 不带默认值，
 * 但本项目 {@code application.yml} 里 redis 块<b>没有 database 这一项</b>、password 也是空值，
 * 照搬会在启动阶段直接抛 {@code IllegalArgumentException: Could not resolve placeholder}。
 * 这里统一补上默认值，既修掉了这个问题，也让缺配置时能优雅降级而不是满屏报错。
 *
 * @author <a>程序员haoy</a>
 */
@Configuration
public class RedissonConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private Integer redisPort;

    /** yml 里 password 为空时解析成空串，这里给默认值兜底，避免占位符解析失败 */
    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    /** 本项目 yml 未配置 database，默认 0 号库 */
    @Value("${spring.data.redis.database:0}")
    private Integer redisDatabase;

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        String address = "redis://" + redisHost + ":" + redisPort;
        SingleServerConfig singleServerConfig = config.useSingleServer()
                .setAddress(address)
                .setDatabase(redisDatabase)
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(10)
                .setIdleConnectionTimeout(30000)
                .setConnectTimeout(5000)
                .setTimeout(3000)
                .setRetryAttempts(3)
                .setRetryInterval(1500);
        // 有密码才设置，空串会被 Redisson 当成「带空密码」处理而连接失败
        if (redisPassword != null && !redisPassword.isEmpty()) {
            singleServerConfig.setPassword(redisPassword);
        }
        return Redisson.create(config);
    }
}
