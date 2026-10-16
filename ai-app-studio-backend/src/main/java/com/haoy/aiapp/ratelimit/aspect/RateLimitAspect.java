package com.haoy.aiapp.ratelimit.aspect;

import com.haoy.aiapp.exception.BusinessException;
import com.haoy.aiapp.exception.ErrorCode;
import com.haoy.aiapp.model.entity.User;
import com.haoy.aiapp.ratelimit.annotation.RateLimit;
import com.haoy.aiapp.service.user.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.Duration;

/**
 * 限流切面
 * <p>
 * 用 AOP 在标注了 {@link RateLimit} 的方法执行前拦一道，基于 Redisson 的令牌桶
 * （{@link RRateLimiter}）做分布式限流。之所以用 Redis 而不是本地计数器：
 * 应用可能部署多实例，本地计数各算各的，凑不出「全站 5 次/分钟」这个语义。
 *
 * @author <a>程序员haoy</a>
 */
@Aspect
@Component
@Slf4j
public class RateLimitAspect {

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private UserService userService;

    /**
     * 限流总开关
     * <p>
     * 为什么需要它：本机联调时 USER 维度会因为「拿不到登录态」降级成 IP 维度，
     * 而所有本机请求的 IP 都是 127.0.0.1 —— 等于全站共用一把桶。
     * 连着跑几次 Vue 生成 + 可视化修改很容易超过 5 次/分钟，
     * 然后就会被自己的限流挡住，排查半天才发现不是 bug。
     * 默认 true（保持生产语义），联调时在 yml 里置 false 即可，代码不用动。
     */
    @Value("${ratelimit.enabled:true}")
    private boolean enabled;

    @Before("@annotation(rateLimit)")
    public void doBefore(JoinPoint point, RateLimit rateLimit) {
        if (!enabled) {
            return;
        }
        String key = generateRateLimitKey(point, rateLimit);
        // 使用 Redisson 的分布式限流器
        RRateLimiter rateLimiter = redissonClient.getRateLimiter(key);
        // ⚠️ 必须设过期时间，否则 Redis 里的 key 永不过期、长期运行内存只增不减
        rateLimiter.expire(Duration.ofHours(1));
        // 设置限流器参数：每个时间窗口允许的请求数和时间窗口长度
        rateLimiter.trySetRate(RateType.OVERALL, rateLimit.rate(), rateLimit.rateInterval(),
                RateIntervalUnit.SECONDS);
        // 尝试获取令牌，拿不到就限流
        if (!rateLimiter.tryAcquire(1)) {
            log.warn("触发限流: key={}", key);
            throw new BusinessException(ErrorCode.TOO_MANY_REQUEST, rateLimit.message());
        }
    }

    /**
     * 生成限流 key
     * <p>
     * 统一以 {@code rate_limit:} 开头，方便在 redis-cli 里 {@code keys rate_limit:*} 排查；
     * 再按维度拼上「能唯一标识请求方」的部分 —— 接口用方法名、用户用 ID、IP 用地址。
     * <p>
     * 这里有个降级设计：USER 维度拿不到登录用户时（未登录 / 请求上下文缺失）
     * 自动退化成 IP 维度，而不是直接放行。宁可拦得粗一点，也不能让未登录请求绕过限流。
     */
    private String generateRateLimitKey(JoinPoint point, RateLimit rateLimit) {
        StringBuilder keyBuilder = new StringBuilder();
        keyBuilder.append("rate_limit:");
        // 添加自定义前缀
        if (!rateLimit.key().isEmpty()) {
            keyBuilder.append(rateLimit.key()).append(":");
        }
        // 根据限流类型生成不同的 key
        switch (rateLimit.limitType()) {
            case API:
                // 接口级别：方法名
                MethodSignature signature = (MethodSignature) point.getSignature();
                Method method = signature.getMethod();
                keyBuilder.append("api:").append(method.getDeclaringClass().getSimpleName())
                        .append(".").append(method.getName());
                break;
            case USER:
                // 用户级别：用户 ID
                try {
                    ServletRequestAttributes attributes =
                            (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
                    if (attributes != null) {
                        HttpServletRequest request = attributes.getRequest();
                        User loginUser = userService.getLoginUser(request);
                        keyBuilder.append("user:").append(loginUser.getId());
                    } else {
                        // 无法获取请求上下文，使用 IP 限流
                        keyBuilder.append("ip:").append(getClientIP());
                    }
                } catch (BusinessException e) {
                    // 未登录用户使用 IP 限流
                    keyBuilder.append("ip:").append(getClientIP());
                }
                break;
            case IP:
                // IP 级别：客户端 IP
                keyBuilder.append("ip:").append(getClientIP());
                break;
            default:
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "不支持的限流类型");
        }
        return keyBuilder.toString();
    }

    /**
     * 获取客户端真实 IP
     * <p>
     * 生产环境前端往往挂在 Nginx 后面，{@code getRemoteAddr()} 拿到的是代理机 IP，
     * 所以按「X-Forwarded-For → X-Real-IP → RemoteAddr」的顺序取。
     * XFF 可能是多级代理拼出来的逗号串，取第一段（最靠近客户端的那个）。
     */
    private String getClientIP() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return "unknown";
        }
        HttpServletRequest request = attributes.getRequest();
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // 处理多级代理的情况
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip != null ? ip : "unknown";
    }
}
