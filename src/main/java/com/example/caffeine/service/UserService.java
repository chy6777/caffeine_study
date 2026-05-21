package com.example.caffeine.service;

import com.example.caffeine.entity.User;
import com.example.caffeine.repository.UserRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.*;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 用户服务 — 基础篇 + 进阶篇
 *
 * 演示三种缓存操作方式：
 *   1. @Cacheable 注解（声明式）      → 第一阶段
 *   2. 手动 Cache API（命令式）       → 第一阶段
 *   3. @CachePut / @Caching 组合      → 第二阶段
 */
@Service
@Slf4j
public class UserService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    @Qualifier("writeCacheManager")
    private CacheManager writeCacheManager;

    @Autowired
    @Qualifier("accessCacheManager")
    private CacheManager accessCacheManager;

    /** 手动缓存（第一阶段对比学习用） */
    private final Cache<Long, User> manualCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(Duration.ofMinutes(10))
            .recordStats()
            .build();

    /** sync=true 并发测试的 DB 查询计数器（第二阶段） */
    private final AtomicInteger syncTestDbCount = new AtomicInteger(0);

    // ====================================================================
    //  第一阶段：@Cacheable 声明式缓存
    // ====================================================================

    /**
     * 走 expireAfterWrite 缓存（默认）
     *
     * unless = "#result == null"：返回 null 时不缓存
     * ⚠️ 这就是缓存穿透隐患！不存在的 id 每次都查库
     */
    @Cacheable(value = "users", key = "#id",
               cacheManager = "writeCacheManager",
               unless = "#result == null")
    public User getUserById(Long id) {
        log.info("[DB QUERY] 查询用户: id={}", id);
        return userRepository.findById(id).orElse(null);
    }

    /**
     * 走 expireAfterAccess 缓存（对比实验用）
     */
    @Cacheable(value = "users-access", key = "#id",
               cacheManager = "accessCacheManager",
               unless = "#result == null")
    public User getUserByIdAccess(Long id) {
        log.info("[DB QUERY] 查询用户(Access缓存): id={}", id);
        return userRepository.findById(id).orElse(null);
    }

    // ====================================================================
    //  第一阶段：手动缓存操作
    // ====================================================================

    public User getUserByIdManual(Long id) {
        User user = manualCache.getIfPresent(id);
        if (user != null) {
            log.info("[CACHE HIT - MANUAL] id={}", id);
            return user;
        }
        log.info("[DB QUERY - MANUAL] id={}", id);
        user = userRepository.findById(id).orElse(null);
        if (user != null) manualCache.put(id, user);
        return user;
    }

    // ====================================================================
    //  第一阶段：Debug 模式
    // ====================================================================

    public Map<String, Object> getUserDebug(Long id) {
        Map<String, Object> result = new LinkedHashMap<>();
        long start = System.nanoTime();

        // 尝试从 writeCache 获取
        org.springframework.cache.Cache springCache = writeCacheManager.getCache("users");
        User user = null;
        boolean cacheHit = false;

        if (springCache != null) {
            var wrapper = springCache.get(id);
            if (wrapper != null && wrapper.get() != null) {
                user = (User) wrapper.get();
                cacheHit = true;
            }
        }

        if (!cacheHit) {
            user = userRepository.findById(id).orElse(null);
            if (user != null && springCache != null) {
                springCache.put(id, user);
            }
        }

        long elapsed = System.nanoTime() - start;

        result.put("data", user);
        result.put("from", cacheHit ? "CACHE" : "DATABASE");
        result.put("cacheHit", cacheHit);
        result.put("penetrationRisk", !cacheHit && user == null);
        result.put("queryTime", String.format("%.3fms", elapsed / 1_000_000.0));

        if (!cacheHit && user == null) {
            result.put("warning",
                    "该 key 未缓存空值，反复查询会每次都打到数据库（缓存穿透）！"
                    + "解决方案见第三阶段。");
        }

        return result;
    }

    // ====================================================================
    //  第一阶段：CRUD
    // ====================================================================

    public User createUser(User user) {
        log.info("[DB INSERT] 新增用户: name={}", user.getName());
        return userRepository.save(user);
    }

    /** 更新 → 清除两个缓存 */
    @Caching(evict = {
            @CacheEvict(value = "users", key = "#user.id",
                        cacheManager = "writeCacheManager"),
            @CacheEvict(value = "users-access", key = "#user.id",
                        cacheManager = "accessCacheManager")
    })
    public User updateUser(User user) {
        log.info("[DB UPDATE] 更新用户: id={}", user.getId());
        return userRepository.save(user);
    }

    /** 删除 → 清除两个缓存 */
    @Caching(evict = {
            @CacheEvict(value = "users", key = "#id",
                        cacheManager = "writeCacheManager"),
            @CacheEvict(value = "users-access", key = "#id",
                        cacheManager = "accessCacheManager")
    })
    public void deleteUser(Long id) {
        log.info("[DB DELETE] 删除用户: id={}", id);
        userRepository.deleteById(id);
    }

    // ====================================================================
    //  第二阶段：@CachePut — 更新后直接刷新缓存
    // ====================================================================

    /**
     * @CachePut：执行方法后，把返回值写入缓存（覆盖旧值）
     *
     * 与 @CacheEvict 对比：
     *   - @CachePut → 更新后缓存立即有新值，下次读取直接命中
     *   - @CacheEvict → 删除缓存，下次读取会 miss 一次再查库
     */
    @CachePut(value = "users", key = "#user.id",
              cacheManager = "writeCacheManager")
    public User updateUserWithCachePut(User user) {
        log.info("[DB UPDATE + CachePut] id={}", user.getId());
        return userRepository.save(user);
    }

    /**
     * @CacheEvict 更新（对比用）
     */
    @Caching(evict = {
            @CacheEvict(value = "users", key = "#user.id",
                        cacheManager = "writeCacheManager"),
            @CacheEvict(value = "users-access", key = "#user.id",
                        cacheManager = "accessCacheManager")
    })
    public User updateUserWithCacheEvict(User user) {
        log.info("[DB UPDATE + CacheEvict] id={}", user.getId());
        return userRepository.save(user);
    }

    // ====================================================================
    //  第二阶段：sync=true 并发保护
    // ====================================================================

    /**
     * sync = true：同一 key 并发查询时，只有一个线程执行查库，其他线程等待结果
     *
     * sync=true 的作用：
     *   - 当缓存过期时，多个并发请求中只有一个会执行方法体（查库）
     *   - 其他线程等待该线程执行完毕，直接使用其结果
     *   - 有效防止缓存击穿
     *
     * 注意事项：
     *   - 只对同一 key 的并发有效，不同 key 各自独立
     *   - unless 不支持与 sync=true 同时使用
     */
    @Cacheable(value = "sync-test", key = "#id",
               cacheManager = "writeCacheManager",
               sync = true)
    public User getUserWithSync(Long id) {
        syncTestDbCount.incrementAndGet();
        log.info("[DB QUERY - SYNC] id={} (这是唯一的数据库查询)", id);
        // 模拟慢查询
        try { Thread.sleep(50); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return userRepository.findById(id).orElse(null);
    }

    public int getAndResetSyncTestDbCount() {
        return syncTestDbCount.getAndSet(0);
    }

    // ====================================================================
    //  第二阶段：SpEL 条件缓存
    // ====================================================================

    /**
     * condition：方法执行前判断，决定是否走缓存
     * unless：方法执行后判断，决定是否缓存结果
     *
     * 这里演示：只缓存 id < 100 的用户（热点数据），null 结果不缓存
     */
    @Cacheable(value = "users-conditional", key = "#id",
               cacheManager = "writeCacheManager",
               condition = "#id < 100",
               unless = "#result == null")
    public User getUserConditional(Long id) {
        log.info("[DB QUERY - CONDITIONAL] id={}", id);
        return userRepository.findById(id).orElse(null);
    }

    // ====================================================================
    //  第二阶段：复合条件查询（自定义 KeyGenerator）
    // ====================================================================

    @Cacheable(value = "user-search", keyGenerator = "customKeyGenerator",
               cacheManager = "writeCacheManager")
    public List<User> searchUsers(String name, Integer age) {
        log.info("[DB QUERY - SEARCH] name={}, age={}", name, age);
        if (name != null && age != null) {
            return userRepository.findByNameContaining(name).stream()
                    .filter(u -> u.getAge() >= age)
                    .toList();
        }
        return userRepository.findByNameContaining(name != null ? name : "");
    }

    // ====================================================================
    //  缓存驱逐（通过 CacheManager）
    // ====================================================================

    /**
     * 通过 CacheManager 驱逐指定 key 的缓存
     * 用于并发测试前清空缓存，模拟缓存过期
     */
    public void evictUserCache(Long id) {
        var wc = writeCacheManager.getCache("users");
        if (wc != null) wc.evict(id);
        var ac = accessCacheManager.getCache("users-access");
        if (ac != null) ac.evict(id);
    }

    // ====================================================================
    //  辅助方法
    // ====================================================================

    public Map<String, Object> getManualCacheStatsMap() {
        Map<String, Object> stats = new LinkedHashMap<>();
        var cs = manualCache.stats();
        stats.put("type", "手动缓存 (expireAfterWrite 10min)");
        stats.put("size", manualCache.estimatedSize());
        stats.put("hitCount", cs.hitCount());
        stats.put("missCount", cs.missCount());
        stats.put("hitRate", String.format("%.2f%%", cs.hitRate() * 100));
        stats.put("evictionCount", cs.evictionCount());
        return stats;
    }

    public long getManualCacheSize() { return manualCache.estimatedSize(); }
    public void clearManualCache() { manualCache.invalidateAll(); }
}