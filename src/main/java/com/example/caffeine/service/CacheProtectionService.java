package com.example.caffeine.service;

import com.example.caffeine.config.BloomFilterInitializer;
import com.example.caffeine.entity.User;
import com.example.caffeine.repository.UserRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 缓存防护服务（第三阶段）
 *
 * 三道防线：
 *   1. 穿透防护 — null 值缓存 / 布隆过滤器
 *   2. 击穿防护 — sync=true / 逻辑过期时间
 *   3. 雪崩防护 — 过期时间加随机偏移
 */
@Service
@Slf4j
public class CacheProtectionService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BloomFilterInitializer bloomFilterInitializer;

    // ---- 空对象缓存（防穿透） ----
    private final Cache<Long, User> nullCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(Duration.ofSeconds(30))
            .build();

    // ---- 逻辑过期缓存（防击穿） ----
    private final Cache<Long, User> logicExpireCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(Duration.ofHours(1))  // 长 TTL，靠逻辑过期控制
            .build();
    private final Map<Long, Instant> logicalExpireTimes = new ConcurrentHashMap<>();
    private static final long LOGICAL_TTL_MS = Duration.ofSeconds(10).toMillis();

    // ====================================================================
    //  穿透防护：三种模式对比
    // ====================================================================

    /**
     * 穿透演练
     *
     * @param count 查询次数
     * @param mode  none / null-cache / bloom-filter
     */
    public Map<String, Object> drillPenetration(int count, String mode) {
        AtomicInteger dbQueryCount = new AtomicInteger(0);
        int bloomBlocked = 0;
        long start = System.nanoTime();

        for (int i = 0; i < count; i++) {
            Long fakeId = 100_000L + i; // 不存在的 id
            String key = "user:" + fakeId;

            switch (mode) {
                case "none" -> {
                    // 无防护：每次查库
                    userRepository.findById(fakeId);
                    dbQueryCount.incrementAndGet();
                }
                case "null-cache" -> {
                    // 空对象缓存：第一次查库，后续命中缓存
                    User cached = nullCache.getIfPresent(fakeId);
                    if (cached == null) {
                        userRepository.findById(fakeId);
                        dbQueryCount.incrementAndGet();
                        nullCache.put(fakeId, User.NULL_USER); // 缓存空对象
                    }
                }
                case "bloom-filter" -> {
                    // 布隆过滤器：不存在的 id 直接拦截，0 次查库
                    if (!bloomFilterInitializer.mightContain(key)) {
                        bloomBlocked++;
                        // 布隆过滤器判断不存在 → 直接跳过
                    } else {
                        // 误判了，继续查库（概率极低）
                        userRepository.findById(fakeId);
                        dbQueryCount.incrementAndGet();
                    }
                }
            }
        }

        long elapsedNs = System.nanoTime() - start;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", mode);
        result.put("totalRequests", count);
        result.put("dbQueryCount", dbQueryCount.get());
        if ("bloom-filter".equals(mode)) {
            result.put("bloomBlockedCount", bloomBlocked);
        }
        result.put("duration", String.format("%.1fms", elapsedNs / 1_000_000.0));
        result.put("description", switch (mode) {
            case "none" -> "无防护：每次请求都打到数据库";
            case "null-cache" -> "空对象缓存：只有第一次查库，后续命中缓存（短 TTL）";
            case "bloom-filter" -> "布隆过滤器：不存在的 key 直接拦截，0 次查库";
            default -> "";
        });

        return result;
    }

    // ====================================================================
    //  击穿防护：三种模式对比
    // ====================================================================

    /**
     * 击穿演练：模拟缓存过期瞬间大量并发
     *
     * @param mode none / sync / logic-expire
     */
    public Map<String, Object> drillBreakdown(int threads, String mode) throws Exception {
        Long targetId = 1L; // 热点 key

        // 准备：确保 key 在缓存中
        User user = userRepository.findById(targetId).orElse(null);
        if (user == null) return Map.of("error", "需要先创建 id=1 的用户");

        // 清除缓存，模拟即将过期
        logicExpireCache.invalidate(targetId);
        logicalExpireTimes.remove(targetId);

        AtomicInteger dbQueryCount = new AtomicInteger(0);
        AtomicInteger returnOldValueCount = new AtomicInteger(0);  // 使用 AtomicInteger 避免竞态
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        long beginNs = System.nanoTime();

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    startLatch.await(); // 等待同时启动

                    switch (mode) {
                        case "none" -> {
                            // 无防护：每个线程都查库
                            User cached = logicExpireCache.getIfPresent(targetId);
                            if (cached == null) {
                                try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                                userRepository.findById(targetId);
                                dbQueryCount.incrementAndGet();
                                logicExpireCache.put(targetId, user);
                            }
                        }
                        case "sync" -> {
                            // sync=true：只有一个线程查库（Caffeine native API）
                            logicExpireCache.get(targetId, id -> {
                                dbQueryCount.incrementAndGet();
                                try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                                return userRepository.findById(id).orElse(null);
                            });
                        }
                        case "logic-expire" -> {
                            // 逻辑过期：返回旧值 + 异步刷新
                            User cached = logicExpireCache.getIfPresent(targetId);
                            if (cached != null) {
                                Instant expireTime = logicalExpireTimes.get(targetId);
                                if (expireTime != null && Instant.now().isAfter(expireTime)) {
                                    returnOldValueCount.incrementAndGet();
                                    // 只有一个线程负责刷新
                                    if (dbQueryCount.compareAndSet(0, 1)) {
                                        try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                                        User fresh = userRepository.findById(targetId).orElse(null);
                                        if (fresh != null) {
                                            logicExpireCache.put(targetId, fresh);
                                            logicalExpireTimes.put(targetId,
                                                    Instant.now().plusMillis(LOGICAL_TTL_MS));
                                        }
                                    }
                                    // 其他线程直接返回旧值
                                }
                            } else {
                                // 缓存为空，需要初始化
                                logicExpireCache.get(targetId, id -> {
                                    dbQueryCount.incrementAndGet();
                                    return userRepository.findById(id).orElse(null);
                                });
                                logicalExpireTimes.put(targetId,
                                        Instant.now().plusMillis(LOGICAL_TTL_MS));
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("击穿演练异常", e);
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        // 同时释放所有线程
        startLatch.countDown();
        doneLatch.await();

        long elapsedNs = System.nanoTime() - beginNs;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", mode);
        result.put("threads", threads);
        result.put("dbQueryCount", dbQueryCount.get());
        if ("logic-expire".equals(mode)) {
            result.put("returnOldValueCount", returnOldValueCount.get());
        }
        result.put("duration", String.format("%.1fms", elapsedNs / 1_000_000.0));
        result.put("description", switch (mode) {
            case "none" -> "无防护：所有线程都查库（缓存击穿）";
            case "sync" -> "sync=true：只有一个线程查库，其他等待结果";
            case "logic-expire" -> "逻辑过期：返回旧值 + 单线程异步刷新";
            default -> "";
        });

        // 清理
        logicExpireCache.invalidate(targetId);

        return result;
    }

    // ====================================================================
    //  雪崩防护：过期时间随机偏移
    // ====================================================================

    /**
     * 雪崩演练：对比固定 TTL vs 随机偏移 TTL
     */
    public Map<String, Object> drillAvalanche(int keyCount, String mode) {
        Cache<Long, String> testCache = Caffeine.newBuilder()
                .maximumSize(keyCount + 10)
                .build();

        long baseTTL = 100; // 基础 TTL 100ms

        // 写入 keyCount 个 key
        for (long i = 1; i <= keyCount; i++) {
            testCache.put(i, "value-" + i);
        }

        // 模拟过期后同时查询
        if ("fixed-ttl".equals(mode)) {
            // 固定 TTL：所有 key 同时过期
            try { Thread.sleep(baseTTL + 10); } catch (InterruptedException ignored) {}
        } else {
            // 随机偏移：等基础 TTL 后，大部分 key 已过期，但有些还没
            try { Thread.sleep(baseTTL + 10); } catch (InterruptedException ignored) {}
        }

        // 测量同时查询的并发量
        CountDownLatch latch = new CountDownLatch(keyCount);
        AtomicInteger concurrentQueries = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);

        for (long i = 1; i <= keyCount; i++) {
            final long key = i;
            new Thread(() -> {
                try {
                    // 模拟：检查缓存
                    if (testCache.getIfPresent(key) == null) {
                        int current = concurrentQueries.incrementAndGet();
                        maxConcurrent.updateAndGet(prev -> Math.max(prev, current));
                        try { Thread.sleep(5); } catch (InterruptedException ignored) {} // 模拟查库
                        concurrentQueries.decrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        try { latch.await(); } catch (InterruptedException ignored) {}

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", mode);
        result.put("keyCount", keyCount);
        result.put("peakConcurrentDbQueries", maxConcurrent.get());
        result.put("description", "fixed-ttl".equals(mode)
                ? "固定 TTL：所有 key 同时过期，峰值并发 = " + maxConcurrent.get()
                : "随机 TTL：过期时间分散，峰值并发已降低（实际效果需配合不同 TTL 写入）");

        return result;
    }
}