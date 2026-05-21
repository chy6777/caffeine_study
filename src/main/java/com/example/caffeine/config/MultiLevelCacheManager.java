package com.example.caffeine.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 多级缓存管理器（L1: Caffeine 本地缓存）
 *
 * 负责管理本地缓存的生命周期和统计。
 * 使用独立的 Caffeine 实例（不走 Spring Cache），更透明、更灵活。
 *
 * 两个 L1 缓存：
 *   - userCache：正常用户数据
 *   - nullValueCache：空值缓存（防穿透），TTL 更短（1分钟）
 */
@Component
@Slf4j
public class MultiLevelCacheManager {

    // L1 本地缓存：按类型区分
    private final Cache<String, Object> userCache;
    private final Cache<String, Object> nullValueCache; // 空值缓存（防穿透）

    // 缓存统计（使用 LongAdder 替代 synchronized，高并发性能更好）
    private final Map<String, AtomicCacheStats> statsMap = new ConcurrentHashMap<>();

    public MultiLevelCacheManager() {
        // 用户缓存：较大容量，短过期
        this.userCache = Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(Duration.ofMinutes(10))
                .recordStats()
                .build();

        // 空值缓存：容量适中，短过期（防穿透，但不过多占用内存）
        this.nullValueCache = Caffeine.newBuilder()
                .maximumSize(200)
                .expireAfterWrite(Duration.ofMinutes(1))
                .recordStats()
                .build();

        // 初始化统计
        statsMap.put("L1_USER", new AtomicCacheStats());
        statsMap.put("L1_NULL", new AtomicCacheStats());
        statsMap.put("L2_REDIS", new AtomicCacheStats());
        statsMap.put("DB", new AtomicCacheStats());

        log.info("[MultiLevel] 多级缓存管理器初始化完成");
    }

    // ========== L1 操作 ==========

    public Object getFromL1(String key) {
        Object value = userCache.getIfPresent(key);
        if (value != null) {
            statsMap.get("L1_USER").hit();
            return value;
        }
        // 检查空值缓存
        value = nullValueCache.getIfPresent(key);
        if (value != null) {
            statsMap.get("L1_NULL").hit();
            return value; // 返回缓存中的空对象
        }
        statsMap.get("L1_USER").miss();
        return null;
    }

    public void putToL1(String key, Object value) {
        if (value == null) {
            nullValueCache.put(key, com.example.caffeine.entity.User.NULL_USER);
            statsMap.get("L1_NULL").put();
        } else {
            userCache.put(key, value);
            statsMap.get("L1_USER").put();
        }
    }

    public void evictL1(String key) {
        userCache.invalidate(key);
        nullValueCache.invalidate(key);
        log.debug("[L1] 本地缓存失效: {}", key);
    }

    public void evictLocalCache(String key) {
        evictL1(key);
    }

    // ========== 统计 ==========

    public void recordL2Hit() { statsMap.get("L2_REDIS").hit(); }
    public void recordL2Miss() { statsMap.get("L2_REDIS").miss(); }
    public void recordDbHit() { statsMap.get("DB").hit(); }
    public void recordDbMiss() { statsMap.get("DB").miss(); }

    public Map<String, Object> getLevelStats() {
        Map<String, Object> result = new ConcurrentHashMap<>();

        // L1 统计
        CacheStats userStats = userCache.stats();
        CacheStats nullStats = nullValueCache.stats();
        long totalL1Hit = userStats.hitCount() + nullStats.hitCount();
        long totalL1Miss = userStats.missCount() + nullStats.missCount();
        long totalL1Req = totalL1Hit + totalL1Miss;

        result.put("l1", Map.of(
                "name", "Caffeine (L1)",
                "hitCount", totalL1Hit,
                "missCount", totalL1Miss,
                "hitRate", totalL1Req > 0 ? String.format("%.1f%%", totalL1Hit * 100.0 / totalL1Req) : "N/A",
                "avgLatency", "~0.001ms (纳秒级)"
        ));

        // L2 统计
        AtomicCacheStats l2Stats = statsMap.get("L2_REDIS");
        long l2Total = l2Stats.hitCount() + l2Stats.missCount();
        result.put("l2", Map.of(
                "name", "Redis (L2)",
                "hitCount", l2Stats.hitCount(),
                "missCount", l2Stats.missCount(),
                "hitRate", l2Total > 0 ? String.format("%.1f%%", l2Stats.hitCount() * 100.0 / l2Total) : "N/A",
                "avgLatency", "~0.5-2ms (毫秒级)"
        ));

        // DB 统计
        AtomicCacheStats dbStats = statsMap.get("DB");
        result.put("db", Map.of(
                "name", "Database (L3)",
                "queryCount", dbStats.hitCount() + dbStats.missCount(),
                "avgLatency", "~10-50ms (毫秒级)"
        ));

        // 总结
        result.put("summary", String.format(
                "L1 缓存命中率 %s，L2 缓存命中率 %s，数据库查询次数 %d",
                ((Map<?, ?>) result.get("l1")).get("hitRate"),
                ((Map<?, ?>) result.get("l2")).get("hitRate"),
                dbStats.hitCount() + dbStats.missCount()
        ));

        return result;
    }

    // ========== 内部类：使用 LongAdder 替代 synchronized ==========

    static class AtomicCacheStats {
        private final LongAdder hitCount = new LongAdder();
        private final LongAdder missCount = new LongAdder();
        private final LongAdder putCount = new LongAdder();

        void hit()  { hitCount.increment(); }
        void miss() { missCount.increment(); }
        void put()  { putCount.increment(); }

        long hitCount()  { return hitCount.sum(); }
        long missCount() { return missCount.sum(); }
        long putCount()  { return putCount.sum(); }
    }
}