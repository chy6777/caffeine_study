package com.example.caffeine.controller;

import com.example.caffeine.entity.User;
import com.example.caffeine.repository.UserRepository;
import com.example.caffeine.service.CacheDegradationService;
import com.example.caffeine.service.CacheProtectionService;
import com.example.caffeine.service.MultiLevelCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 故障演练控制器（第三阶段）
 *
 * 故意制造问题 → 观察现象 → 理解原理 → 验证解决方案
 */
@RestController
@RequestMapping("/api/drill")
@Slf4j
public class FaultDrillController {

    @Autowired
    private CacheProtectionService protectionService;

    @Autowired
    private CacheDegradationService degradationService;

    @Autowired
    private MultiLevelCacheService multiLevelCacheService;

    @Autowired
    private UserRepository userRepository;

    /** 演练结果记录（限制最大 100 条，防止内存泄漏） */
    private final List<Map<String, Object>> drillResults =
            Collections.synchronizedList(new LimitedArrayList<>(100));

    // ---- #29/30/31 穿透演练 ----
    @PostMapping("/penetrate")
    public Map<String, Object> drillPenetrate(
            @RequestParam(defaultValue = "1000") int count,
            @RequestParam(defaultValue = "bloom-filter") String mode) {

        Map<String, Object> result = protectionService.drillPenetration(count, mode);
        result.put("drillType", "缓存穿透");
        drillResults.add(result);
        return result;
    }

    // ---- #32/33/34 击穿演练 ----
    @PostMapping("/breakdown")
    public Map<String, Object> drillBreakdown(
            @RequestParam(defaultValue = "100") int threads,
            @RequestParam(defaultValue = "sync") String mode) throws Exception {

        Map<String, Object> result = protectionService.drillBreakdown(threads, mode);
        result.put("drillType", "缓存击穿");
        drillResults.add(result);
        return result;
    }

    // ---- #35/36 雪崩演练 ----
    @PostMapping("/avalanche")
    public Map<String, Object> drillAvalanche(
            @RequestParam(defaultValue = "50") int keyCount,
            @RequestParam(defaultValue = "random-ttl") String mode) {

        Map<String, Object> result = protectionService.drillAvalanche(keyCount, mode);
        result.put("drillType", "缓存雪崩");
        drillResults.add(result);
        return result;
    }

    // ---- #37 模拟 Redis 宕机 ----
    @PostMapping("/redis-down")
    public Map<String, Object> redisDown() {
        Map<String, Object> result = degradationService.simulateRedisDown();
        result.put("nextStep", "GET /api/advanced/users/1 验证降级后仍能正常响应");
        result.put("recoveryStep", "POST /api/drill/redis-recover 恢复 Redis");
        drillResults.add(result);
        return result;
    }

    // ---- #38 模拟 Redis 恢复 ----
    @PostMapping("/redis-recover")
    public Map<String, Object> redisRecover() {
        Map<String, Object> result = degradationService.simulateRedisRecover();
        result.put("nextStep", "GET /api/drill/verify-consistency 验证数据一致性");
        drillResults.add(result);
        return result;
    }

    // ---- #39 验证一致性 ----
    @GetMapping("/verify-consistency")
    public Map<String, Object> verifyConsistency(
            @RequestParam(defaultValue = "1") Long id) {

        User dbUser = userRepository.findById(id).orElse(null);
        User l1User = multiLevelCacheService.getUserCaffeineOnly(id);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", id);
        result.put("dbName", dbUser != null ? dbUser.getName() : null);
        result.put("l1CaffeineName", l1User != null ? l1User.getName() : null);
        result.put("consistent", Objects.equals(
                dbUser != null ? dbUser.getName() : null,
                l1User != null ? l1User.getName() : null));

        result.put("note", "如需验证 Redis，请确保 Redis 可用并重新查询以回填 L2");
        return result;
    }

    // ---- #40 演练结果汇总 ----
    @GetMapping("/result")
    public Map<String, Object> drillResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalDrills", drillResults.size());
        result.put("results", drillResults);
        return result;
    }

    // ====================================================================
    //  内部类：带最大容量限制的 ArrayList
    // ====================================================================

    /**
     * 当添加元素超过 maxSize 时，自动移除最早的元素
     */
    private static class LimitedArrayList<E> extends ArrayList<E> {
        private final int maxSize;

        LimitedArrayList(int maxSize) {
            super(maxSize);
            this.maxSize = maxSize;
        }

        @Override
        public boolean add(E e) {
            if (size() >= maxSize) {
                remove(0); // 移除最旧的
            }
            return super.add(e);
        }
    }
}