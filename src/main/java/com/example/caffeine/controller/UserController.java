package com.example.caffeine.controller;

import com.example.caffeine.entity.User;
import com.example.caffeine.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.*;

@RestController
@RequestMapping("/api/users")
@Slf4j
public class UserController {

    @Autowired
    private UserService userService;

    // ---- #1 查用户（默认 writeCache，?cacheType=access 切换） ----
    @GetMapping("/{id}")
    public User getUser(@PathVariable Long id,
                        @RequestParam(defaultValue = "write") String cacheType) {
        return "access".equals(cacheType)
                ? userService.getUserByIdAccess(id)
                : userService.getUserById(id);
    }

    // ---- #2 手动缓存方式 ----
    @GetMapping("/{id}/manual")
    public User getUserManual(@PathVariable Long id) {
        return userService.getUserByIdManual(id);
    }

    // ---- #3 Debug 模式 ----
    @GetMapping("/{id}/debug")
    public Map<String, Object> getUserDebug(@PathVariable Long id) {
        return userService.getUserDebug(id);
    }

    // ---- #4 新增 ----
    @PostMapping
    public User createUser(@RequestBody User user) {
        return userService.createUser(user);
    }

    // ---- #5 更新（@CacheEvict） ----
    @PutMapping
    public User updateUser(@RequestBody User user) {
        return userService.updateUser(user);
    }

    // ---- #6 删除 ----
    @DeleteMapping("/{id}")
    public Map<String, String> deleteUser(@PathVariable Long id) {
        userService.deleteUser(id);
        return Map.of("message", "用户 " + id + " 已删除，缓存已同步清除");
    }

    // ====================================================================
    //  第二阶段接口
    // ====================================================================

    // ---- #16 更新 V2: @CachePut（更新后直接刷新缓存） ----
    @PutMapping("/update-v2")
    public Map<String, Object> updateUserV2(@RequestBody User user) {
        User saved = userService.updateUserWithCachePut(user);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("user", saved);
        result.put("strategy", "CachePut");
        result.put("operation", "更新数据库 + 直接刷新缓存");
        result.put("nextReadWillHitCache", true);
        result.put("note", "适用于：更新后对象完整、希望下次读取立即命中");
        return result;
    }

    // ---- #17 更新 V3: @CacheEvict（更新后删除缓存，对比用） ----
    @PutMapping("/update-v3")
    public Map<String, Object> updateUserV3(@RequestBody User user) {
        User saved = userService.updateUserWithCacheEvict(user);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("user", saved);
        result.put("strategy", "CacheEvict");
        result.put("operation", "更新数据库 + 删除缓存");
        result.put("nextReadWillHitCache", false);
        result.put("note", "下次读取会 miss 一次查库后重新缓存。适用于：不确定更新后缓存值是否正确");
        return result;
    }

    // ---- #19 sync=true 并发测试（同 key → 生效） ----
    @GetMapping("/{id}/sync-test")
    public Map<String, Object> syncTest(@PathVariable Long id,
                                        @RequestParam(defaultValue = "100") int threads)
            throws Exception {

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        // 清除 sync-test 缓存
        userService.getAndResetSyncTestDbCount();

        long beginNs = System.nanoTime();

        for (int i = 0; i < threads; i++) {
            CompletableFuture.runAsync(() -> {
                try {
                    startLatch.await();
                    userService.getUserWithSync(id);
                } catch (Exception e) {
                    log.error("sync-test 异常", e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();

        long elapsedNs = System.nanoTime() - beginNs;
        int dbCount = userService.getAndResetSyncTestDbCount();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenario", threads + " 个线程查询同一个 key (id=" + id + ")");
        result.put("syncProtection", "有效");
        result.put("dbQueryCount", dbCount);
        result.put("cacheHitCount", threads - dbCount);
        result.put("totalTime", String.format("%.1fms", elapsedNs / 1_000_000.0));
        result.put("lesson", "sync=true 确保同一 key 只有一个线程查库，其他等待结果");

        return result;
    }

    // ---- #20 sync=true 失效演示（不同 key → 无效） ----
    @GetMapping("/sync-test-fail")
    public Map<String, Object> syncTestFail(
            @RequestParam(defaultValue = "100") int keyCount,
            @RequestParam(defaultValue = "100") int threads) throws Exception {

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        userService.getAndResetSyncTestDbCount();

        long beginNs = System.nanoTime();

        for (int i = 0; i < threads; i++) {
            final long userId = (i % keyCount) + 1;
            CompletableFuture.runAsync(() -> {
                try {
                    startLatch.await();
                    userService.getUserWithSync(userId); // 不同 key！
                } catch (Exception e) {
                    log.error("sync-test-fail 异常", e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();

        long elapsedNs = System.nanoTime() - beginNs;
        int dbCount = userService.getAndResetSyncTestDbCount();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenario", threads + " 个线程查询 " + keyCount + " 个不同的 key");
        result.put("syncProtection", "无效");
        result.put("dbQueryCount", dbCount);
        result.put("totalTime", String.format("%.1fms", elapsedNs / 1_000_000.0));
        result.put("lesson", "sync=true 只对同一 key 的并发查询有效，不同 key 各自独立");

        return result;
    }

    // ---- #18 复合条件查询（自定义 KeyGenerator） ----
    @GetMapping("/search")
    public Map<String, Object> searchUsers(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) Integer age) {
        List<User> users = userService.searchUsers(name, age);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("data", users);
        result.put("generatedCacheKey", "users:name=" + name + ",age=" + age);
        return result;
    }

    // ---- #23 SpEL 条件缓存 ----
    @GetMapping("/{id}/conditional")
    public Map<String, Object> conditional(@PathVariable Long id) {
        User user = userService.getUserConditional(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("data", user);
        result.put("cached", id < 100);
        result.put("condition", id < 100
                ? "id < 100 → 满足，执行缓存"
                : "id < 100 → 不满足，跳过缓存");
        if (user == null) {
            result.put("unless", "result == null → 满足，不缓存结果");
        }
        return result;
    }
}