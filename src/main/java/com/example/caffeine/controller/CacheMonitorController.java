package com.example.caffeine.controller;

import com.example.caffeine.service.UserService;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/cache")
@Slf4j
public class CacheMonitorController {

    @Autowired
    @Qualifier("writeCacheManager")
    private CacheManager writeCacheManager;

    @Autowired
    @Qualifier("accessCacheManager")
    private CacheManager accessCacheManager;

    @Autowired
    private UserService userService;

    // ---- #7 全局命中率统计 ----
    @GetMapping("/stats")
    public Map<String, Object> globalStats() {
        Map<String, Object> result = new LinkedHashMap<>();

        CaffeineCache wc = getCaffeineCache(writeCacheManager, "users");
        if (wc != null) result.put("writeCache", buildStatsMap(wc, "expireAfterWrite 5min"));

        CaffeineCache ac = getCaffeineCache(accessCacheManager, "users-access");
        if (ac != null) result.put("accessCache", buildStatsMap(ac, "expireAfterAccess 5min"));

        result.put("manualCache", userService.getManualCacheStatsMap());
        return result;
    }

    // ---- #8 按 key 查看状态 ----
    @GetMapping("/stats/{key}")
    public Map<String, Object> keyStats(@PathVariable Long key) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);

        CaffeineCache wc = getCaffeineCache(writeCacheManager, "users");
        if (wc != null) {
            var nc = wc.getNativeCache();
            boolean present = nc.getIfPresent(key) != null;
            result.put("inWriteCache", present);
        }

        CaffeineCache ac = getCaffeineCache(accessCacheManager, "users-access");
        if (ac != null) {
            var nc = ac.getNativeCache();
            boolean present = nc.getIfPresent(key) != null;
            result.put("inAccessCache", present);
        }

        return result;
    }

    // ---- #9 缓存大小 ----
    @GetMapping("/size")
    public Map<String, Object> cacheSize() {
        Map<String, Object> result = new LinkedHashMap<>();
        CaffeineCache wc = getCaffeineCache(writeCacheManager, "users");
        if (wc != null) result.put("writeCache (users)", wc.getNativeCache().estimatedSize());
        CaffeineCache ac = getCaffeineCache(accessCacheManager, "users-access");
        if (ac != null) result.put("accessCache (users-access)", ac.getNativeCache().estimatedSize());
        result.put("manualCache", userService.getManualCacheSize());
        return result;
    }

    // ---- #10 时间线（使用 Instant.now() 计算） ----
    @GetMapping("/timeline/{id}")
    public Map<String, Object> timeline(@PathVariable Long id) {
        Map<String, Object> result = new LinkedHashMap<>();

        CaffeineCache wc = getCaffeineCache(writeCacheManager, "users");
        if (wc != null) {
            var nc = wc.getNativeCache();
            Object value = nc.getIfPresent(id);
            if (value != null) {
                Map<String, Object> tl = new LinkedHashMap<>();
                tl.put("cacheType", "expireAfterWrite");
                tl.put("cached", true);
                nc.policy().expireVariably().ifPresent(policy -> {
                    policy.getExpiresAfter(id, TimeUnit.MILLISECONDS).ifPresent(remainingMs -> {
                        long configured = Duration.ofMinutes(5).toMillis();
                        long ageMs = configured - remainingMs;
                        tl.put("approxWriteTime", Instant.now().minusMillis(ageMs).toString());
                        tl.put("expireTime", Instant.now().plusMillis(remainingMs).toString());
                        tl.put("remainingTTL", formatMs(remainingMs));
                        tl.put("note", "expireAfterWrite: 过期时间从写入计算，访问不会延长");
                    });
                });
                result.put("writeCache", tl);
            } else {
                result.put("writeCache", Map.of("cached", false, "note", "请先 GET /api/users/" + id));
            }
        }

        CaffeineCache ac = getCaffeineCache(accessCacheManager, "users-access");
        if (ac != null) {
            var nc = ac.getNativeCache();
            Object value = nc.getIfPresent(id);
            if (value != null) {
                Map<String, Object> tl = new LinkedHashMap<>();
                tl.put("cacheType", "expireAfterAccess");
                tl.put("cached", true);
                nc.policy().expireVariably().ifPresent(policy -> {
                    policy.getExpiresAfter(id, TimeUnit.MILLISECONDS).ifPresent(remainingMs -> {
                        long configured = Duration.ofMinutes(5).toMillis();
                        long sinceLastAccess = configured - remainingMs;
                        tl.put("lastAccessTime", Instant.now().minusMillis(sinceLastAccess).toString());
                        tl.put("expireTime", Instant.now().plusMillis(remainingMs).toString());
                        tl.put("remainingTTL", formatMs(remainingMs));
                        tl.put("note", "expireAfterAccess: 每次访问重置过期时间！");
                    });
                });
                result.put("accessCache", tl);
            }
        }

        if (result.isEmpty()) result.put("note", "该 key 在所有缓存中都不存在。请先查询再查看时间线。");
        return result;
    }


    // ---- #11 清空 ----
    @DeleteMapping("/clear")
    public Map<String, String> clearAll() {
        Map<String, String> result = new LinkedHashMap<>();
        var wc = writeCacheManager.getCache("users");
        if (wc != null) { wc.clear(); result.put("writeCache", "已清空"); }
        var ac = accessCacheManager.getCache("users-access");
        if (ac != null) { ac.clear(); result.put("accessCache", "已清空"); }
        userService.clearManualCache();
        result.put("manualCache", "已清空");
        return result;
    }

    // ---- #14 maximumSize 淘汰行为观察（W-TinyLFU 演示） ----
    @GetMapping("/evict-demo")
    public Map<String, Object> evictDemo(@RequestParam(defaultValue = "3") int maxSize) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("maxSize", maxSize);

        // 实验 1：基本淘汰
        var cache1 = Caffeine.newBuilder().maximumSize(maxSize).build();
        List<String> steps1 = new ArrayList<>();
        for (long i = 1; i <= maxSize + 2; i++) {
            cache1.put(i, "v" + i);
            steps1.add(String.format("put(%d) → 缓存: %s (大小: %d)",
                    i, new TreeSet<>(cache1.asMap().keySet()), cache1.estimatedSize()));
        }
        result.put("experiment1_基本淘汰", steps1);

        // 实验 2：频率感知淘汰（W-TinyLFU）
        var cache2 = Caffeine.newBuilder().maximumSize(maxSize).build();
        List<String> steps2 = new ArrayList<>();
        cache2.put(1L, "v1"); cache2.put(2L, "v2"); cache2.put(3L, "v3");
        steps2.add("插入 1,2,3 → 缓存: " + new TreeSet<>(cache2.asMap().keySet()));

        for (int j = 0; j < 10; j++) cache2.getIfPresent(1L);
        steps2.add("访问 key=1 共 10 次 → 缓存: " + new TreeSet<>(cache2.asMap().keySet()));

        cache2.put(4L, "v4");
        steps2.add("put(4) → 缓存: " + new TreeSet<>(cache2.asMap().keySet())
                + " ← key=1 频率高，更不容易被淘汰");

        cache2.put(5L, "v5");
        steps2.add("put(5) → 缓存: " + new TreeSet<>(cache2.asMap().keySet()));

        result.put("experiment2_频率感知淘汰(W-TinyLFU)", steps2);
        result.put("lesson", "Caffeine 使用 W-TinyLFU 算法：频繁访问的条目存活率更高，优于 LRU。"
                + " W-TinyLFU 维护一个频率 sketch，新条目需要击败'门卫'才能进入缓存。");
        return result;
    }

    // ---- #15 简单预热 ----
    @PostMapping("/warmup-simple")
    public Map<String, Object> warmupSimple() {
        long start = System.currentTimeMillis();
        var wc = writeCacheManager.getCache("users");
        if (wc != null) wc.clear();

        // 通过 @Cacheable 方法加载进缓存
        for (long i = 1; i <= 20; i++) {
            userService.getUserById(i);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("loaded", 20);
        result.put("elapsedMs", System.currentTimeMillis() - start);
        CaffeineCache cc = getCaffeineCache(writeCacheManager, "users");
        result.put("cacheSize", cc != null ? cc.getNativeCache().estimatedSize() : 0);
        result.put("note", "基础预热完成！第二阶段有带优先级的高级版本: POST /api/cache/warmup");
        return result;
    }

    // ====================================================================
    //  工具方法
    // ====================================================================

    private CaffeineCache getCaffeineCache(CacheManager manager, String name) {
        var cache = manager.getCache(name);
        return (cache instanceof CaffeineCache cc) ? cc : null;
    }

    private Map<String, Object> buildStatsMap(CaffeineCache cc, String typeDesc) {
        Map<String, Object> info = new LinkedHashMap<>();
        var nc = cc.getNativeCache();
        CacheStats stats = nc.stats();
        info.put("type", typeDesc);
        info.put("size", nc.estimatedSize());
        info.put("hitCount", stats.hitCount());
        info.put("missCount", stats.missCount());
        info.put("hitRate", String.format("%.2f%%", stats.hitRate() * 100));
        info.put("missRate", String.format("%.2f%%", stats.missRate() * 100));
        info.put("evictionCount", stats.evictionCount());
        info.put("avgLoadPenalty", String.format("%.3fms", stats.averageLoadPenalty() / 1_000_000.0));
        return info;
    }

    private String formatMs(long ms) {
        if (ms < 1000) return ms + "ms";
        if (ms < 60_000) return String.format("%.1fs", ms / 1000.0);
        return String.format("%.1fmin", ms / 60_000.0);
    }
}