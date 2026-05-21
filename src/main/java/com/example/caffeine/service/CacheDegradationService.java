package com.example.caffeine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 缓存降级服务（第三阶段）
 *
 * 核心职责：
 *   1. 检测 Redis 是否可用（两层检查：手动模拟标记 + 实际 PING）
 *   2. 模拟 Redis 宕机/恢复（故障演练用）
 *   3. 降级期间的写操作记录 → 恢复后自动同步
 */
@Service
@Slf4j
public class CacheDegradationService {

    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;

    /** 故障演练：手动模拟 Redis 宕机 */
    private final AtomicBoolean simulatedDown = new AtomicBoolean(false);

    /** 降级期间修改的 key（恢复后需要同步到 Redis） */
    private final List<String> pendingSyncKeys = new CopyOnWriteArrayList<>();

    /**
     * Redis 是否可用
     * 两层检查：手动模拟标记 + 实际连接测试（PING 命令）
     */
    public boolean isRedisAvailable() {
        if (simulatedDown.get()) return false;
        if (redisTemplate == null) return false;
        try {
            return Boolean.TRUE.equals(
                    redisTemplate.execute((RedisCallback<Boolean>) conn -> {
                        conn.ping();
                        return true;
                    })
            );
        } catch (Exception e) {
            log.debug("[DEGRADATION] Redis 不可用: {}", e.getMessage());
            return false;
        }
    }

    /** 模拟 Redis 宕机 */
    public Map<String, Object> simulateRedisDown() {
        simulatedDown.set(true);
        log.warn("[DEGRADATION] Redis 已模拟宕机，后续请求将降级为纯本地缓存");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "Redis 已模拟宕机");
        result.put("degradationStrategy", "L1(Caffeine) + DB 兜底");
        result.put("impact", "性能会下降（L2缓存不可用），但服务不中断");
        return result;
    }

    /** 模拟 Redis 恢复 + 同步降级期间的数据 */
    public Map<String, Object> simulateRedisRecover() {
        simulatedDown.set(false);
        Map<String, Object> result = new LinkedHashMap<>();

        int synced = 0;
        if (redisTemplate != null) {
            for (String key : pendingSyncKeys) {
                try {
                    // 从数据库重新加载并写入 Redis
                    if (key.startsWith("user:")) {
                        Long id = Long.parseLong(key.substring(5));
                        redisTemplate.delete(key);
                        synced++;
                    }
                } catch (Exception e) {
                    log.warn("[DEGRADATION] 同步失败: key={}", key);
                }
            }
        }

        int pendingSize = pendingSyncKeys.size();
        pendingSyncKeys.clear();

        result.put("status", "Redis 已恢复");
        result.put("syncedKeys", synced);
        result.put("pendingKeysBeforeRecover", pendingSize);
        result.put("note", pendingSize > 0
                ? "降级期间的写入已清理，请重新查询以刷新 Redis 缓存"
                : "降级期间无写入操作，无需同步");

        log.info("[DEGRADATION] Redis 已恢复，同步 {} 个 key", synced);
        return result;
    }

    /** 记录降级期间的写操作 key */
    public void recordPendingSync(String key) {
        pendingSyncKeys.add(key);
        log.debug("[DEGRADATION] 记录待同步 key: {}", key);
    }

    public boolean isSimulatedDown() { return simulatedDown.get(); }
}