package com.example.caffeine.service;

import com.example.caffeine.config.MultiLevelCacheManager;
import com.example.caffeine.entity.User;
import com.example.caffeine.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 多级缓存服务（第三阶段核心）
 *
 * 请求流程：
 *   L1 (Caffeine 本地缓存, 纳秒级)
 *     ↓ miss
 *   L2 (Redis 远程缓存, 毫秒级)
 *     ↓ miss
 *   DB (数据库, 十毫秒级)
 *     ↓ 回填 L1 + L2
 *   返回结果
 *
 * 设计原则：
 *   - L1 通过 MultiLevelCacheManager 管理（独立 Caffeine 实例）
 *   - Redis 不可用时自动降级为 L1 + DB
 *   - 统计各级命中率
 */
@Service
@Slf4j
public class MultiLevelCacheService {

    @Autowired
    private UserRepository userRepository;

    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private MultiLevelCacheManager cacheManager;

    @Autowired
    private CacheDegradationService degradationService;

    // Redis key 前缀
    private static final String REDIS_KEY_PREFIX = "user:";
    // 默认 Redis 过期时间
    private static final int REDIS_TTL_MINUTES = 30;

    // 逻辑过期时间记录（用于异步刷新演示）
    private static final long LOGICAL_EXPIRE_MS = 5 * 60 * 1000; // 5 分钟

    // 命中统计
    private long l2Hits = 0, dbQueries = 0;

    // ====================================================================
    //  多级缓存查询（标准流程）
    // ====================================================================

    public User getUser(Long id) {
        String key = REDIS_KEY_PREFIX + id;

        // ========== L1: Caffeine 本地缓存 ==========
        Object l1Value = cacheManager.getFromL1(key);
        if (l1Value != null) {
            if (l1Value instanceof User user) {
                if (user.isNullUser()) {
                    log.info("[L1 HIT] id={}, 返回空对象（防穿透）", id);
                    return null;
                }
                log.debug("[L1 HIT] id={}, 本地缓存命中", id);
                return user;
            }
        }

        // ========== L2: Redis 远程缓存 ==========
        if (degradationService.isRedisAvailable()) {
            try {
                Object l2Value = redisTemplate.opsForValue().get(key);
                if (l2Value instanceof User user) {
                    cacheManager.putToL1(key, user); // 回填 L1
                    cacheManager.recordL2Hit();
                    l2Hits++;
                    log.debug("[L2 HIT] id={}, Redis 命中，已回填本地缓存", id);
                    return user;
                }
            } catch (Exception e) {
                log.warn("[L2 ERROR] Redis 查询失败: {}", e.getMessage());
                cacheManager.recordL2Miss();
            }
        }

        // ========== L3: 数据库 ==========
        log.debug("[DB QUERY] id={}, 查询数据库", id);
        User user = userRepository.findById(id).orElse(null);
        dbQueries++;
        cacheManager.recordDbHit();

        if (user != null) {
            // 回填 L1
            cacheManager.putToL1(key, user);
            // 回填 L2
            if (degradationService.isRedisAvailable()) {
                try {
                    int randomTTL = User.randomExpireOffset(REDIS_TTL_MINUTES, 10);
                    redisTemplate.opsForValue().set(key, user, randomTTL, TimeUnit.MINUTES);
                } catch (Exception e) {
                    log.warn("[L2 WRITE ERROR] {}", e.getMessage());
                }
            }
        } else {
            // 空对象缓存（防穿透）
            cacheManager.putToL1(key, null);
        }

        return user;
    }

    // ====================================================================
    //  异步刷新演示（逻辑过期 → 返回旧值 + 后台刷新）
    // ====================================================================

    /**
     * 逻辑过期的多级缓存查询
     *
     * 当 key 逻辑过期时：
     *   1. 立即返回旧值（不阻塞调用方）
     *   2. 提交异步任务刷新缓存
     */
    public User getUserWithAsyncRefresh(Long id) {
        String key = REDIS_KEY_PREFIX + id;

        Object l1Value = cacheManager.getFromL1(key);
        if (l1Value instanceof User user && !user.isNullUser()) {
            // 检查逻辑过期时间
            if (user.getLogicExpireTime() != null &&
                user.getLogicExpireTime().isBefore(Instant.now())) {
                // 逻辑过期：返回旧值 + 异步刷新
                log.info("[ASYNC] id={}, 缓存逻辑过期，返回旧值并异步刷新", id);
                asyncRefresh(id, key);
            } else {
                log.debug("[L1 HIT] id={}, 缓存有效", id);
            }
            return user;
        }

        // 缓存不存在，同步加载
        return loadAndCache(id, key);
    }

    /**
     * 异步刷新缓存（在新线程中执行）
     */
    private void asyncRefresh(Long id, String key) {
        new Thread(() -> {
            try {
                User fresh = userRepository.findById(id).orElse(null);
                if (fresh != null) {
                    fresh.setLogicExpireTime(Instant.now().plusMillis(LOGICAL_EXPIRE_MS));
                    cacheManager.putToL1(key, fresh);

                    if (degradationService.isRedisAvailable()) {
                        redisTemplate.opsForValue().set(key, fresh, 60, TimeUnit.MINUTES);
                    }
                    log.info("[ASYNC REFRESH] id={} 缓存已刷新", id);
                }
            } catch (Exception e) {
                log.error("[ASYNC REFRESH] id={} 刷新失败: {}", id, e.getMessage());
            }
        }, "async-refresh-" + id).start();
    }

    private User loadAndCache(Long id, String key) {
        log.debug("[DB QUERY - async] id={}", id);
        User user = userRepository.findById(id).orElse(null);
        if (user != null) {
            user.setLogicExpireTime(Instant.now().plusMillis(LOGICAL_EXPIRE_MS));
            cacheManager.putToL1(key, user);
            if (degradationService.isRedisAvailable()) {
                try {
                    redisTemplate.opsForValue().set(key, user, 60, TimeUnit.MINUTES);
                } catch (Exception ignored) {}
            }
        }
        return user;
    }

    // ====================================================================
    //  仅 Caffeine / 仅 Redis（基准测试用）
    // ====================================================================

    public User getUserCaffeineOnly(Long id) {
        String key = REDIS_KEY_PREFIX + id;
        Object l1Value = cacheManager.getFromL1(key);
        if (l1Value instanceof User user && !user.isNullUser()) return user;
        User user = userRepository.findById(id).orElse(null);
        if (user != null) cacheManager.putToL1(key, user);
        return user;
    }

    public User getUserRedisOnly(Long id) {
        String key = REDIS_KEY_PREFIX + id;
        if (degradationService.isRedisAvailable()) {
            try {
                User user = (User) redisTemplate.opsForValue().get(key);
                if (user != null) return user;
            } catch (Exception ignored) {}
        }
        User user = userRepository.findById(id).orElse(null);
        if (user != null && degradationService.isRedisAvailable()) {
            try {
                redisTemplate.opsForValue().set(key, user, 30, TimeUnit.MINUTES);
            } catch (Exception ignored) {}
        }
        return user;
    }

    // ====================================================================
    //  缓存管理
    // ====================================================================

    public void invalidateL1(Long id) {
        cacheManager.evictL1(REDIS_KEY_PREFIX + id);
    }

    /**
     * 多级缓存一致性删除
     */
    public void invalidateCache(Long id) {
        String key = REDIS_KEY_PREFIX + id;

        // 1. 删除 L1
        cacheManager.evictL1(key);

        // 2. 删除 L2
        if (degradationService.isRedisAvailable()) {
            try {
                redisTemplate.delete(key);
            } catch (Exception e) {
                log.warn("[INVALIDATE] 删除 Redis 缓存失败: {}", e.getMessage());
            }
        }

        log.info("[INVALIDATE] 已删除 L1+L2 缓存: {}", key);
    }

    public void invalidateAll() {
        // L1 清理由 MultiLevelCacheManager 内部处理
        l2Hits = dbQueries = 0;
    }

    // ====================================================================
    //  统计信息
    // ====================================================================

    public Map<String, Object> getLevelStats() {
        Map<String, Object> stats = new LinkedHashMap<>();

        // L1 统计（从 MultiLevelCacheManager 获取）
        Map<String, Object> managerStats = cacheManager.getLevelStats();
        stats.putAll(managerStats);

        stats.put("redisAvailable", degradationService.isRedisAvailable());

        return stats;
    }
}