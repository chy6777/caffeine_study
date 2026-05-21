package com.example.caffeine.config;

import com.example.caffeine.entity.User;
import com.example.caffeine.repository.UserRepository;
import com.example.caffeine.service.UserService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 缓存预热器（第二阶段）
 *
 * 设计思路：
 *   - 按优先级分批加载：HIGH（热点数据）→ MEDIUM → LOW
 *   - 预热失败的 key 记录下来，不影响其他 key
 *   - 支持手动触发 + 状态查询
 *
 * 为什么不一次性加载全部？
 *   热点数据优先加载，保证核心请求在预热完成前就能命中缓存。
 *
 * 状态机：NOT_STARTED → RUNNING → COMPLETED / FAILED
 */
@Component
@Slf4j
public class CacheWarmupRunner implements CommandLineRunner {

    @Autowired(required = false)
    private UserRepository userRepository;

    @Autowired(required = false)
    private UserService userService;

    private volatile WarmupStatus status = new WarmupStatus();

    @Override
    public void run(String... args) {
        // 默认不自动预热（通过 application.yml 的 app.cache.warmup-on-startup 控制）
        // 用户可通过 POST /api/cache/warmup 手动触发
        log.info("[WARMUP] 预热器就绪。手动触发: POST /api/cache/warmup");
    }

    /**
     * 触发预热（异步执行）
     */
    public WarmupStatus triggerWarmup() {
        if (userRepository == null || userService == null) {
            status.setStatus("FAILED");
            status.setError("依赖未注入");
            return status;
        }

        // 重置状态
        status = new WarmupStatus();
        status.setStatus("RUNNING");
        status.setStartTime(Instant.now());

        // 异步执行预热
        CompletableFuture.runAsync(this::doWarmup);

        return status;
    }

    private void doWarmup() {
        try {
            List<User> allUsers = userRepository.findAll();

            // HIGH: 热点用户（hot=true）
            List<User> highPriority = allUsers.stream()
                    .filter(u -> Boolean.TRUE.equals(u.getHot()))
                    .toList();
            loadBatch(highPriority, "HIGH");

            // MEDIUM: id 6~15
            List<User> mediumPriority = allUsers.stream()
                    .filter(u -> !Boolean.TRUE.equals(u.getHot()) && u.getId() <= 15)
                    .toList();
            loadBatch(mediumPriority, "MEDIUM");

            // LOW: 其余
            List<User> lowPriority = allUsers.stream()
                    .filter(u -> u.getId() > 15)
                    .toList();
            loadBatch(lowPriority, "LOW");

            status.setStatus("COMPLETED");
            status.setElapsedMs(System.currentTimeMillis() - status.getStartTime().toEpochMilli());
            log.info("[WARMUP] 预热完成: 已加载 {}, 失败 {}, 耗时 {}ms",
                    status.getTotalLoaded(), status.getTotalFailed(), status.getElapsedMs());

        } catch (Exception e) {
            status.setStatus("FAILED");
            status.setError(e.getMessage());
            log.error("[WARMUP] 预热失败", e);
        }
    }

    private void loadBatch(List<User> users, String priority) {
        status.setCurrentPriority(priority);
        PriorityStats ps = new PriorityStats();
        ps.setTotal(users.size());
        ps.setStatus("IN_PROGRESS");
        status.getPriorityStats().put(priority, ps);

        for (User user : users) {
            try {
                userService.getUserById(user.getId());
                ps.setLoaded(ps.getLoaded() + 1);
                status.incrementTotalLoaded();
            } catch (Exception e) {
                ps.setFailed(ps.getFailed() + 1);
                status.addFailedKey(user.getId());
                log.warn("[WARMUP] 加载失败: id={}, error={}", user.getId(), e.getMessage());
            }
        }

        ps.setStatus("DONE");
        log.info("[WARMUP] [{}] 完成: {}/{}", priority, ps.getLoaded(), ps.getTotal());
    }

    public WarmupStatus getStatus() {
        return status;
    }

    // ========================================================================
    //  状态数据结构
    // ========================================================================

    @Data
    public static class WarmupStatus {
        private String status = "NOT_STARTED";
        private String currentPriority;
        private Map<String, PriorityStats> priorityStats = new LinkedHashMap<>();
        private AtomicInteger totalLoaded = new AtomicInteger(0);
        private AtomicInteger totalFailed = new AtomicInteger(0);
        private volatile long elapsedMs;
        private volatile Instant startTime;
        private List<Long> failedKeys = new CopyOnWriteArrayList<>();
        private String error;

        public void incrementTotalLoaded() { totalLoaded.incrementAndGet(); }
        public void addFailedKey(Long id) { failedKeys.add(id); }
    }

    @Data
    public static class PriorityStats {
        private int total;
        private int loaded;
        private int failed;
        private String status = "PENDING";
    }
}