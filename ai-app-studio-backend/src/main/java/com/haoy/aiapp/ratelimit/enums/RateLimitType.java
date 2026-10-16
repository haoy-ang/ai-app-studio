package com.haoy.aiapp.ratelimit.enums;

/**
 * 限流维度
 * <p>
 * 三种维度决定了限流 key 的粒度：
 * <ul>
 *   <li>{@link #API} —— 整个接口共用一把锁，谁先来谁消耗令牌，适合保护昂贵的下游依赖；</li>
 *   <li>{@link #USER} —— 按登录用户隔离，一个人刷不影响别人，是 AI 对话最常用的维度；</li>
 *   <li>{@link #IP} —— 按来源 IP 隔离，用于「还没登录」也要拦的场景。</li>
 * </ul>
 *
 * @author <a>程序员haoy</a>
 */
public enum RateLimitType {

    /**
     * 接口级别限流
     */
    API,

    /**
     * 用户级别限流
     */
    USER,

    /**
     * IP 级别限流
     */
    IP
}
