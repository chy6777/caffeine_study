package com.example.caffeine.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * 用户实体
 *
 * 使用 Lombok 简化代码：
 *   @Data       → getter/setter/toString/equals/hashCode
 *   @Builder    → 链式构造
 *   @NoArgsConstructor / @AllArgsConstructor → JPA 要求
 */
@Entity
@Table(name = "users")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 空对象常量 — 用于缓存穿透防护
     * 当查询不存在的 id 时，缓存 NULL_USER 而非 null，
     * 这样后续相同 id 的查询会命中缓存，避免每次都打到数据库。
     */
    public static final User NULL_USER = User.builder()
            .id(-1L).name("NULL").email("null@null.com").age(0).hot(false)
            .build();

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    private Integer age;

    @Column(name = "is_hot")
    private Boolean hot;

    @Column(name = "created_at")
    private Instant createdAt;

    /**
     * 逻辑过期时间（第三阶段 — 击穿防护-逻辑过期方案）
     *
     * 不同于物理过期（Caffeine TTL），逻辑过期是在业务层维护的过期时间。
     * 读取时检查是否逻辑过期：过期则返回旧值 + 异步刷新，不阻塞调用方。
     * 标记 @Transient 表示不持久化到数据库。
     */
    @Transient
    @JsonIgnore
    private Instant logicExpireTime;

    /**
     * 判断是否为空对象（防穿透用）
     */
    public boolean isNullUser() {
        return this == NULL_USER || (id != null && id == -1L);
    }

    /**
     * 生成随机过期时间偏移（用于雪崩防护）
     *
     * 例如 baseMinutes=30, maxOffsetMinutes=10 → 结果范围 30~40 分钟
     * 这样不同 key 的过期时间分散在一个时间窗口内，避免同时过期。
     */
    public static int randomExpireOffset(int baseMinutes, int maxOffsetMinutes) {
        return baseMinutes + (int) (Math.random() * (maxOffsetMinutes + 1));
    }
}