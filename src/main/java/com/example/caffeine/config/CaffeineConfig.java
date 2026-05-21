package com.example.caffeine.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.concurrent.Executor;

/**
 * Caffeine 缓存配置（第一阶段核心）
 *
 * 定义两套缓存管理器，用于对比实验：
 *   writeCacheManager  → expireAfterWrite（写入后固定过期）
 *   accessCacheManager → expireAfterAccess（访问后重置过期）
 *
 * expireAfterWrite vs expireAfterAccess：
 *   - expireAfterWrite：写入后固定时间过期，无论中间访问多少次。适合 session、验证码等。
 *   - expireAfterAccess：最后一次访问后计时，常访问的数据一直存活。适合热点数据、配置项。
 */
@Configuration
@Slf4j
public class CaffeineConfig {

    /**
     * 缓存 A：expireAfterWrite — 写入后固定时间过期
     *
     * 无论中间访问多少次，到期即过期。
     * 适用：session、验证码、限流计数器等变化频率可预测的数据。
     */
    @Bean("writeCacheManager")
    @Primary
    public CacheManager writeCacheManager() {
        log.info("[CONFIG] writeCacheManager → expireAfterWrite=5min, maxSize=1000");
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(Duration.ofMinutes(5))
                .recordStats()  // 开启统计，用于命中率监控
        );
        return manager;
    }

    /**
     * 缓存 B：expireAfterAccess — 最后访问后计时过期
     *
     * 每次访问都重置过期计时器，常访问的数据一直存活。
     * 适用：热点数据、用户信息、配置项、字典表。
     */
    @Bean("accessCacheManager")
    public CacheManager accessCacheManager() {
        log.info("[CONFIG] accessCacheManager → expireAfterAccess=5min, maxSize=1000");
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterAccess(Duration.ofMinutes(5))
                .recordStats()
        );
        return manager;
    }

    /**
     * 异步线程池（第二阶段：缓存预热、第三阶段：异步刷新）
     */
    @Bean("cacheExecutor")
    public Executor cacheExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("cache-async-");
        executor.initialize();
        log.info("[CONFIG] cacheExecutor 线程池初始化完成");
        return executor;
    }
}