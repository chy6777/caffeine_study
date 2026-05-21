package com.example.caffeine.controller;

import com.example.caffeine.entity.User;
import com.example.caffeine.service.CacheConsistencyService;
import com.example.caffeine.service.MultiLevelCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 高级缓存控制器（第三阶段）
 *
 * 多级缓存、异步刷新、一致性方案
 */
@RestController
@RequestMapping("/api/advanced")
@Slf4j
public class AdvancedCacheController {

    @Autowired
    private MultiLevelCacheService multiLevelCacheService;

    @Autowired
    private CacheConsistencyService consistencyService;

    // ---- #24 多级缓存查询（L1→L2→DB） ----
    @GetMapping("/users/{id}")
    public Map<String, Object> getUser(@PathVariable Long id) {
        long start = System.nanoTime();
        User user = multiLevelCacheService.getUser(id);
        long elapsed = System.nanoTime() - start;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("data", user);
        result.put("queryTime", String.format("%.3fms", elapsed / 1_000_000.0));
        result.put("flow", "L1(Caffeine) → L2(Redis) → DB");
        return result;
    }

    // ---- #25 异步刷新（返回旧值 + 后台刷新） ----
    @GetMapping("/users/{id}/async")
    public Map<String, Object> getUserAsync(@PathVariable Long id) {
        // 先确保缓存中有数据
        multiLevelCacheService.getUser(id);

        long start = System.nanoTime();
        User user = multiLevelCacheService.getUserWithAsyncRefresh(id);
        long elapsed = System.nanoTime() - start;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("data", user);
        result.put("queryTime", String.format("%.3fms", elapsed / 1_000_000.0));
        result.put("strategy", "逻辑过期 → 返回旧值 + 后台异步刷新");
        result.put("note", "检查日志可以看到 [ASYNC REFRESH] 刷新记录");
        return result;
    }

    // ---- #26 两级缓存命中率对比 ----
    @GetMapping("/cache/level-stats")
    public Map<String, Object> levelStats() {
        return multiLevelCacheService.getLevelStats();
    }

    // ---- #27 多级缓存一致性删除（Pub/Sub） ----
    @PostMapping("/cache/invalidate/{id}")
    public Map<String, Object> invalidate(@PathVariable Long id) {
        User user = multiLevelCacheService.getUser(id);
        if (user == null) return Map.of("error", "用户不存在: " + id);

        consistencyService.invalidateWithPubSub(id, user);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "缓存无效化指令已广播");
        result.put("userId", id);
        result.put("steps", new String[]{
                "1. 数据库已更新",
                "2. Redis 缓存已删除",
                "3. Pub/Sub 消息已广播（所有实例清除本地 L1）"
        });
        return result;
    }

    // ---- #28 一致性方案对比 ----
    @GetMapping("/consistency/compare")
    public Map<String, Object> consistencyCompare() {
        return consistencyService.getConsistencyComparison();
    }
}