package com.haoy.aiapp;

import dev.langchain4j.community.store.embedding.redis.spring.RedisEmbeddingStoreAutoConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

/**
 * 应用启动类
 * <p>
 * {@code @EnableCaching} 打开 Spring Cache 的注解支持（@Cacheable / @CacheEvict 等），
 * 具体的缓存实现（Redis）由 {@code config/RedisCacheManagerConfig} 提供的 CacheManager 决定。
 */
@EnableCaching
@SpringBootApplication(exclude = {RedisEmbeddingStoreAutoConfiguration.class})
@MapperScan("com.haoy.aiapp.mapper")
public class AiAppStudioApplication {

	public static void main(String[] args) {
		SpringApplication.run(AiAppStudioApplication.class, args);
	}

}
