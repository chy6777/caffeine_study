package com.example.caffeine.service;

import com.example.caffeine.entity.User;
import com.example.caffeine.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多级缓存一致性服务（第三阶段）
 *
 * 提供三种一致性方案的对比：
 *   1. Pub/Sub 实时通知
 *   2. 版本号比对
 *   3. TTL 兜底
 */
@Service
@Slf4j
public class CacheConsistencyService {

    private static final String INVALIDATION_CHANNEL = "cache:invalidate";
    private static final String VERSION_PREFIX = "cache:version:user:";

    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired(required = false)
    private RedisMessageListenerContainer listenerContainer;

    @Autowired
    private MultiLevelCacheService multiLevelCacheService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CacheDegradationService degradationService;

    /** 本地版本号缓存（方案二用） */
    private final Map<Long, Long> localVersions = new ConcurrentHashMap<>();
    private final AtomicLong versionCounter = new AtomicLong(0);

    /** Pub/Sub 收到的无效化消息计数（直接使用 java.util.concurrent.atomic.AtomicInteger） */
    private final AtomicInteger pubSubReceivedCount = new AtomicInteger(0);

    // ====================================================================
    //  方案一：Pub/Sub 实时通知
    // ====================================================================

    /**
     * 初始化 Pub/Sub 监听器
     *
     * 使用 @PostConstruct 在 Bean 初始化完成后注册监听器，
     * 收到消息时调用 handleInvalidation 清除本地 L1 缓存。
     */
    @PostConstruct
    public void init() {
        if (listenerContainer == null) return;
        try {
            MessageListenerAdapter adapter = new MessageListenerAdapter(this, "handleInvalidation");
            adapter.afterPropertiesSet();
            listenerContainer.addMessageListener(adapter, new ChannelTopic(INVALIDATION_CHANNEL));
            log.info("[CONSISTENCY] Pub/Sub 监听器注册成功, channel={}", INVALIDATION_CHANNEL);
        } catch (Exception e) {
            log.warn("[CONSISTENCY] Pub/Sub 注册失败（Redis 可能不可用）: {}", e.getMessage());
        }
    }

    /**
     * 收到无效化消息后的处理
     * 当另一个实例广播"key 已失效"时，清除本地 L1 缓存
     */
    public void handleInvalidation(String message) {
        log.info("[CONSISTENCY] 收到无效化消息: {}", message);
        pubSubReceivedCount.incrementAndGet();

        if (message != null && message.startsWith("user:")) {
            try {
                Long id = Long.parseLong(message.substring(5));
                multiLevelCacheService.invalidateL1(id);
                log.info("[CONSISTENCY] 本地 L1 缓存已清除: id={}", id);
            } catch (NumberFormatException e) {
                log.warn("[CONSISTENCY] 无效消息格式: {}", message);
            }
        }
    }

    /**
     * 广播缓存无效化（写操作时调用）
     *
     * 流程：
     *   1. 更新数据库
     *   2. 删除 Redis 缓存
     *   3. 发布 Pub/Sub 消息 → 所有实例清除本地 L1
     */
    public void invalidateWithPubSub(Long id, User updatedUser) {
        // 1. 更新数据库
        userRepository.save(updatedUser);

        // 2. 删除 Redis
        if (degradationService.isRedisAvailable()) {
            redisTemplate.delete("user:" + id);
        }

        // 3. 广播
        if (degradationService.isRedisAvailable()) {
            redisTemplate.convertAndSend(INVALIDATION_CHANNEL, "user:" + id);
        }

        // 4. 本实例也清除 L1
        multiLevelCacheService.invalidateL1(id);

        log.info("[CONSISTENCY] Pub/Sub 无效化完成: id={}", id);
    }

    // ====================================================================
    //  方案二：版本号比对
    // ====================================================================

    /**
     * 版本号写入：递增 Redis 版本号 + 更新本地版本
     */
    public void writeWithVersion(Long id, User user) {
        userRepository.save(user);

        if (degradationService.isRedisAvailable()) {
            redisTemplate.opsForValue().set("user:" + id, user);
            redisTemplate.opsForValue().increment(VERSION_PREFIX + id);
        }

        localVersions.merge(id, 1L, (old, v) -> old + 1);
        multiLevelCacheService.invalidateL1(id);
    }

    /**
     * 版本号读取：比对本地版本和 Redis 版本
     * 不一致则认为本地缓存过期
     */
    public User readWithVersion(Long id) {
        // 读本地 L1
        User local = multiLevelCacheService.getUserCaffeineOnly(id);

        if (local != null && degradationService.isRedisAvailable()) {
            // 比对版本号
            Object redisVersionObj = redisTemplate.opsForValue().get(VERSION_PREFIX + id);
            Long redisVersion = redisVersionObj != null ? Long.parseLong(redisVersionObj.toString()) : 0L;
            Long localVersion = localVersions.getOrDefault(id, 0L);

            if (redisVersion > localVersion) {
                log.info("[CONSISTENCY] 版本不一致: local={}, redis={} → 刷新 L1",
                        localVersion, redisVersion);
                multiLevelCacheService.invalidateL1(id);
                local = null; // 强制重新查询
            }
        }

        if (local == null) {
            local = multiLevelCacheService.getUser(id);
            if (local != null) {
                localVersions.put(id, versionCounter.incrementAndGet());
            }
        }

        return local;
    }

    // ====================================================================
    //  三种方案对比
    // ====================================================================

    public Map<String, Object> getConsistencyComparison() {
        Map<String, Object> result = new LinkedHashMap<>();

        result.put("schemes", new Object[]{
                Map.of(
                        "name", "Pub/Sub 实时通知",
                        "consistency", "准实时（毫秒级）",
                        "pros", new String[]{"通知及时", "所有实例同步失效", "实现成熟"},
                        "cons", new String[]{"依赖 Redis Pub/Sub 可靠性", "网络抖动可能丢消息"},
                        "suitableFor", "多实例部署、对一致性要求较高"
                ),
                Map.of(
                        "name", "版本号比对",
                        "consistency", "读时校验",
                        "pros", new String[]{"实现简单", "不依赖额外中间件"},
                        "cons", new String[]{"每次读取都要查 Redis 版本号", "多一次 Redis 调用"},
                        "suitableFor", "实例少、读多写少的场景"
                ),
                Map.of(
                        "name", "TTL 兜底",
                        "consistency", "最终一致（过期后一致）",
                        "pros", new String[]{"零额外开发", "零额外依赖"},
                        "cons", new String[]{"过期窗口内数据不一致"},
                        "suitableFor", "对一致性要求不高的参考数据"
                )
        });

        result.put("recommendation", "生产环境建议: Pub/Sub + TTL 兜底组合使用");
        result.put("note", "Pub/Sub 保证实时性，TTL 兜底保证最终一致性（防止消息丢失）");
        result.put("pubSubMessagesReceived", pubSubReceivedCount.get());

        return result;
    }
}