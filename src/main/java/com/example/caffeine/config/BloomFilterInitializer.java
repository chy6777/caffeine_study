package com.example.caffeine.config;

import com.example.caffeine.entity.User;
import com.example.caffeine.repository.UserRepository;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 布隆过滤器初始化（第三阶段 — 穿透防护）
 *
 * 原理：用极小的内存判断一个元素「是否可能存在」
 *   - 返回 false → 一定不存在 → 直接拦截，不查库
 *   - 返回 true  → 可能存在（有误判率） → 继续查库
 *
 * 使用 String key（如 "user:1"）而非 Long id，更灵活：
 *   - 可以扩展到其他实体类型
 *   - key 前缀避免不同类型 id 冲突
 *
 * 适用：缓存穿透防护，拦截大量不存在的 key 查询
 */
@Component
@Slf4j
public class BloomFilterInitializer implements CommandLineRunner {

    @Autowired
    private UserRepository userRepository;

    @Value("${app.bloom-filter.expected-insertions:10000}")
    private int expectedInsertions;

    @Value("${app.bloom-filter.fpp:0.01}")
    private double fpp;

    private BloomFilter<String> bloomFilter;

    @Override
    public void run(String... args) {
        bloomFilter = BloomFilter.create(
                Funnels.stringFunnel(StandardCharsets.UTF_8),
                expectedInsertions,
                fpp
        );

        userRepository.findAll().forEach(user -> {
            if (user.getId() != null) {  // null 检查，防止 NPE
                bloomFilter.put("user:" + user.getId());
            }
        });

        log.info("[BLOOM FILTER] 初始化完成，已加载 {} 条记录，期望误判率: {}",
                userRepository.count(), fpp);
    }

    /**
     * 判断 key 是否可能存在
     */
    public boolean mightContain(String key) {
        return bloomFilter != null && bloomFilter.mightContain(key);
    }

    /**
     * 添加新 key（新增用户时调用）
     */
    public void put(String key) {
        if (bloomFilter != null) {
            bloomFilter.put(key);
        }
    }
}