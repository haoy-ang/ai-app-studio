package com.haoy.aiapp.ratelimit.annotation;

import com.haoy.aiapp.ratelimit.enums.RateLimitType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 限流注解
 * <p>
 * 标在 Controller 方法上，由 {@code RateLimitAspect} 在方法执行前统一拦截。
 * 语义是「每个时间窗口内最多放行 rate 次」。
 * <p>
 * 注意它是 {@code RUNTIME} 保留 —— AOP 靠反射读注解，改成 CLASS 会导致切点匹配不到、限流静默失效。
 *
 * @author <a>程序员haoy</a>
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /**
     * 限流 key 前缀（可选）
     * <p>
     * 同一个方法想拆成多把桶时用得上，比如按应用 ID 分别限流。
     * 留空则只用「维度 + 标识」拼 key。
     */
    String key() default "";

    /**
     * 每个时间窗口允许的请求数
     */
    int rate() default 10;

    /**
     * 时间窗口（秒）
     */
    int rateInterval() default 1;

    /**
     * 限流类型
     */
    RateLimitType limitType() default RateLimitType.USER;

    /**
     * 限流提示信息
     */
    String message() default "请求过于频繁，请稍后再试";
}
