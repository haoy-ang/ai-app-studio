package com.haoy.aiapp.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.Resource;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Redis 缓存管理器配置
 * <p>
 * 用「旁路缓存」模式（Cache-Aside）：查询先看缓存、未命中查库并回写、靠 TTL 自动过期，
 * 不需要在写路径上主动删缓存。这是最主流也最省心的做法。
 * <p>
 * 缓存对象选的是主页「精选应用列表」的第 1~10 页：访问频率高（几乎所有用户都会看）、
 * 更新频率低（精选由管理员手动设置），正好符合「高频读、低频写」这个缓存前提。
 * <p>
 * ⚠️ 序列化器的选择是这里唯二容易踩坑的地方，另一处是 {@link com.haoy.aiapp.common.BaseResponse} 的构造函数，
 * 见 {@link #cacheManager()} 里的注释。
 *
 * @author <a>程序员haoy</a>
 */
@Configuration
public class RedisCacheManagerConfig {

    @Resource
    private RedisConnectionFactory redisConnectionFactory;

    /**
     * 缓存管理器
     * <p>
     * ⚠️ 关于 value 序列化器，这里有一整段「为什么不能用默认配置」的来龙去脉：
     * <ol>
     *   <li><b>默认的 JDK 序列化会把应用搞崩。</b>{@code RedisCacheConfiguration.defaultCacheConfig()} 自带
     *       {@code JdkSerializationRedisSerializer}，反序列化时 {@code ObjectInputStream} 靠
     *       「调用栈上最近的一个用户类加载器」来还原类。而 Spring Data Redis 本身是<b>库类</b>，
     *       由系统类加载器（{@code app}）加载，于是它会把 {@code BaseResponse} 还原成 {@code app} 那一份；
     *       但 controller 的 CGLIB 代理是<b>应用类</b>，在 DevTools 下由 {@code RestartClassLoader} 加载，
     *       它期望的是 {@code RestartClassLoader} 那一份 → 同一个类两个 Class 对象 →
     *       {@code ClassCastException: BaseResponse cannot be cast to BaseResponse}。
     *       生产环境（没有 DevTools）不炸，所以这个坑只在开发期出现，极其迷惑。</li>
     *   <li><b>JSON 序列化必须带「类名元信息」。</b>否则写进 Redis 的只有字段、没有类型，
     *       读回来只能变成 {@code LinkedHashMap}，强转 {@code Page} 时又是 ClassCastException
     *       —— 漏掉这一步会让读回来的对象失去真实类型（下面这行 {@code activateDefaultTyping} 才是正解）。</li>
     *   <li><b>还要注册 {@code JavaTimeModule}。</b>{@code AppVO}/{@code UserVO} 里有
     *       {@code LocalDateTime}（createTime/updateTime/deployedTime），Jackson 默认不认识 Java8 时间类型。</li>
     * </ol>
     * 注意这里的 {@code ObjectMapper} 是<b>自己 new 的</b>，不是容器里那个全局 Bean ——
     * 全局 {@code JsonConfig} 把 Long 序列化成了 String（给前端看的），缓存不该被它影响。
     */
    @Bean
    public CacheManager cacheManager() {
        // value 用「带类型信息的 JSON」：redis-cli 里能直接看懂内容，也保留真实类型
        ObjectMapper objectMapper = new ObjectMapper();
        // ① 支持 Java8 时间类型（AppVO / UserVO 里的 LocalDateTime）
        objectMapper.registerModule(new JavaTimeModule());
        // 时间不要写成 [2026,10,10,...] 这种数组，写成可读的 ISO 字符串
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // ② 关键：把类名（@class）写进 JSON，否则读回来拿不到真实类型
        objectMapper.activateDefaultTyping(LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);
        // ③ 容错：以后给 VO 加字段时，旧缓存不会因为「多出的字段认不出」而整条反序列化失败
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(30))
                // 不缓存 null：否则「查不到」也会被缓存住，等数据真的来了还是返回空
                .disableCachingNullValues()
                // key 用 String 序列化器：redis-cli 里能直接看懂 key，排查时不用先反序列化
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer(objectMapper)));

        return RedisCacheManager.builder(redisConnectionFactory)
                .cacheDefaults(defaultConfig)
                // 精选列表单独给 5 分钟：它是全站访问最频繁的接口，过期时间短一点，
                // 管理员调整精选后最多 5 分钟就能生效（默认 30 分钟太久了）
                .withCacheConfiguration("good_app_page",
                        defaultConfig.entryTtl(Duration.ofMinutes(5)))
                .build();
    }
}
