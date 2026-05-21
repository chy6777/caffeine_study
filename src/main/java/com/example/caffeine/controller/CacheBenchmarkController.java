package com.example.caffeine.controller;

import com.example.caffeine.entity.User;
import com.example.caffeine.repository.UserRepository;
import com.example.caffeine.service.CacheDegradationService;
import com.example.caffeine.service.MultiLevelCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 性能基准测试控制器（第三阶段）
 *
 * 用数据说话：不同缓存策略的性能差异
 */
@RestController
@RequestMapping("/api/benchmark")
@Slf4j
public class CacheBenchmarkController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MultiLevelCacheService multiLevelCacheService;

    @Autowired
    private CacheDegradationService degradationService;

    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    @Qualifier("writeCacheManager")
    private CacheManager writeCacheManager;

    private static final int USER_COUNT = 20;

    // ---- #41 四种模式汇总对比 ----
    @GetMapping("/compare")
    public Map<String, Object> compare(@RequestParam(defaultValue = "1000") int n) {
        Map<String, Object> result = new LinkedHashMap<>();

        // 1. 无缓存（直接查库）
        clearAllCaches();
        result.put("noCache", benchmarkDirectDB(n));

        // 2. 仅 Caffeine
        clearAllCaches();
        result.put("caffeineOnly", benchmarkCaffeineOnly(n));

        // 3. 仅 Redis
        clearAllCaches();
        result.put("redisOnly", benchmarkRedisOnly(n));

        // 4. 多级缓存
        clearAllCaches();
        result.put("multiLevel", benchmarkMultiLevel(n));

        result.put("queryCount", n);
        result.put("note", "第一次查询为缓存 miss（查库），后续查询命中缓存");
        return result;
    }

    // ---- #42 冷启动性能 ----
    @GetMapping("/cold-start")
    public Map<String, Object> coldStart(@RequestParam(defaultValue = "1000") int n) {
        clearAllCaches();
        Map<String, Object> result = benchmarkDirectDB(n);
        result.put("scenario", "冷启动（无缓存，每次查库）");
        return result;
    }

    // ---- #43 预热后性能 ----
    @GetMapping("/pre-warmed")
    public Map<String, Object> preWarmed(@RequestParam(defaultValue = "1000") int n) {
        clearAllCaches();

        // 预热
        long warmupStart = System.nanoTime();
        for (long i = 1; i <= USER_COUNT; i++) {
            multiLevelCacheService.getUser(i);
        }
        long warmupTime = System.nanoTime() - warmupStart;

        // 测试
        Map<String, Object> result = benchmarkMultiLevel(n);
        result.put("scenario", "预热后（缓存已加载）");
        result.put("warmupTime", String.format("%.1fms", warmupTime / 1_000_000.0));

        return result;
    }

    // ====================================================================
    //  基准测试方法
    // ====================================================================

    private Map<String, Object> benchmarkDirectDB(int n) {
        long start = System.nanoTime();
        for (int i = 0; i < n; i++) {
            long id = (i % USER_COUNT) + 1;
            userRepository.findById(id);
        }
        return buildResult("无缓存（直接查库）", n, System.nanoTime() - start);
    }

    private Map<String, Object> benchmarkCaffeineOnly(int n) {
        long start = System.nanoTime();
        for (int i = 0; i < n; i++) {
            long id = (i % USER_COUNT) + 1;
            multiLevelCacheService.getUserCaffeineOnly(id);
        }
        return buildResult("仅 Caffeine (L1)", n, System.nanoTime() - start);
    }

    private Map<String, Object> benchmarkRedisOnly(int n) {
        if (!degradationService.isRedisAvailable()) {
            return Map.of("name", "仅 Redis (L2)", "error", "Redis 不可用，跳过");
        }
        long start = System.nanoTime();
        for (int i = 0; i < n; i++) {
            long id = (i % USER_COUNT) + 1;
            multiLevelCacheService.getUserRedisOnly(id);
        }
        return buildResult("仅 Redis (L2)", n, System.nanoTime() - start);
    }

    private Map<String, Object> benchmarkMultiLevel(int n) {
        long start = System.nanoTime();
        for (int i = 0; i < n; i++) {
            long id = (i % USER_COUNT) + 1;
            multiLevelCacheService.getUser(id);
        }
        return buildResult("多级缓存 (L1+L2)", n, System.nanoTime() - start);
    }

    private Map<String, Object> buildResult(String name, int n, long elapsedNs) {
        Map<String, Object> result = new LinkedHashMap<>();
        double elapsedMs = elapsedNs / 1_000_000.0;
        result.put("name", name);
        result.put("queryCount", n);
        result.put("totalTime", String.format("%.1fms", elapsedMs));
        result.put("avgTime", String.format("%.4fms", elapsedMs / n));
        result.put("qps", (int) (n / (elapsedMs / 1000)));
        return result;
    }

    private void clearAllCaches() {
        // 清除 Spring Cache
        var wc = writeCacheManager.getCache("users");
        if (wc != null) wc.clear();
        // 清除 L1
        multiLevelCacheService.invalidateAll();
        // 清除 Redis
        if (degradationService.isRedisAvailable() && redisTemplate != null) {
            try {
                Set<String> keys = redisTemplate.keys("user:*");
                if (keys != null && !keys.isEmpty()) {
                    redisTemplate.delete(keys);
                }
            } catch (Exception ignored) {}
        }
    }
}