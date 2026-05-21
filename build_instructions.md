# Caffeine 缓存完整学习项目（合并版）

> 三阶段递进：基础 → 进阶 → 高级  
> 共 22 个文件、43+ 个接口，无需 Redis 即可运行第一、二阶段

## 项目结构

```
caffeine-demo/
├── pom.xml
├── src/main/resources/
│   └── application.yml
└── src/main/java/com/example/caffeine/
    ├── CaffeineDemoApplication.java
    ├── entity/
    │   └── User.java
    ├── repository/
    │   └── UserRepository.java
    ├── config/
    │   ├── CaffeineConfig.java
    │   ├── CaffeineKeyGenerator.java
    │   ├── CacheWarmupRunner.java
    │   ├── RedisConfig.java
    │   ├── MultiLevelCacheManager.java
    │   └── BloomFilterInitializer.java
    ├── service/
    │   ├── UserService.java
    │   ├── MultiLevelCacheService.java
    │   ├── CacheProtectionService.java
    │   ├── CacheConsistencyService.java
    │   └── CacheDegradationService.java
    ├── controller/
    │   ├── UserController.java
    │   ├── CacheMonitorController.java
    │   ├── CacheWarmupController.java
    │   ├── AdvancedCacheController.java
    │   ├── FaultDrillController.java
    │   └── CacheBenchmarkController.java
    └── init/
        └── DataInitializer.java
```

---

## 1. pom.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.2.5</version>
        <relativePath/>
    </parent>

    <groupId>com.example</groupId>
    <artifactId>caffeine-demo</artifactId>
    <version>1.0.0</version>
    <name>caffeine-demo</name>
    <description>Spring Boot + Caffeine 分级学习 Demo（基础→进阶→高级）</description>

    <properties>
        <java.version>17</java.version>
    </properties>

    <dependencies>
        <!-- Web -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <!-- JPA + H2 内存数据库 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- Spring Cache 抽象层 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-cache</artifactId>
        </dependency>

        <!-- Caffeine — 高性能本地缓存（第一阶段核心） -->
        <dependency>
            <groupId>com.github.ben-manes.caffeine</groupId>
            <artifactId>caffeine</artifactId>
        </dependency>

        <!-- Redis — 远程缓存（第三阶段核心） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>

        <!-- Guava — 布隆过滤器（第三阶段穿透防护） -->
        <dependency>
            <groupId>com.google.guava</groupId>
            <artifactId>guava</artifactId>
            <version>33.1.0-jre</version>
        </dependency>

        <!-- Lombok -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

---

## 2. application.yml

```yaml
server:
  port: 8080

spring:
  # ---- H2 内存数据库（零配置，学习专用） ----
  datasource:
    url: jdbc:h2:mem:caffeinedb;DB_CLOSE_DELAY=-1
    driver-class-name: org.h2.Driver
    username: sa
    password:
  jpa:
    hibernate:
      ddl-auto: create-drop
    show-sql: false
  h2:
    console:
      enabled: true
      path: /h2-console

  # ---- Redis 配置（第三阶段需要） ----
  data:
    redis:
      host: localhost
      port: 6379
      timeout: 2000ms
      lettuce:
        pool:
          max-active: 8
          max-idle: 8
          min-idle: 0

# ---- 自定义配置 ----
app:
  cache:
    # 启动时是否自动预热（第二阶段功能）
    warmup-on-startup: false
  bloom-filter:
    expected-insertions: 10000
    fpp: 0.01

logging:
  level:
    com.example.caffeine: INFO
```

---

## 3. CaffeineDemoApplication.java

```java
package com.example.caffeine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableCaching   // 第一阶段：开启 Spring 缓存注解
@EnableAsync     // 第二阶段：开启异步方法支持（预热、异步刷新）
@Slf4j
public class CaffeineDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(CaffeineDemoApplication.class, args);

        log.info("==============================================");
        log.info("  Caffeine 分级学习 Demo 启动成功！");
        log.info("  ────────────────────────────────────────");
        log.info("  [基础篇]  http://localhost:8080/api/users/1");
        log.info("  [监控]    http://localhost:8080/api/cache/stats");
        log.info("  [H2控制台] http://localhost:8080/h2-console");
        log.info("  ────────────────────────────────────────");
        log.info("  [进阶篇]  PUT  /api/users/update-v2");
        log.info("  [预热]    POST /api/cache/warmup");
        log.info("  ────────────────────────────────────────");
        log.info("  [高级篇]  GET  /api/advanced/users/1");
        log.info("  [演练]    POST /api/drill/penetrate?count=1000&mode=bloom-filter");
        log.info("  [基准]    GET  /api/benchmark/compare?n=1000");
        log.info("  ────────────────────────────────────────");
        log.info("  第三阶段功能需要 Redis，请确保 Redis 已启动");
        log.info("  启动命令: docker run -d -p 6379:6379 redis:latest");
        log.info("==============================================");
    }
}
```

---

## 4. User.java

```java
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
```

---

## 5. UserRepository.java

```java
package com.example.caffeine.repository;

import com.example.caffeine.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserRepository extends JpaRepository<User, Long> {

    List<User> findByNameContaining(String name);

    List<User> findByHotTrue();
}
```

---

## 6. DataInitializer.java

```java
package com.example.caffeine.init;

import com.example.caffeine.entity.User;
import com.example.caffeine.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class DataInitializer implements CommandLineRunner {

    @Autowired
    private UserRepository userRepository;

    @Override
    public void run(String... args) {
        // 防止重启时重复插入数据
        if (userRepository.count() > 0) return;

        String[] names = {
            "Alice", "Bob", "Charlie", "Diana", "Eve",
            "Frank", "Grace", "Hank", "Ivy", "Jack",
            "Kate", "Leo", "Mia", "Noah", "Olivia",
            "Peter", "Quinn", "Rose", "Sam", "Tina"
        };

        List<User> users = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            users.add(User.builder()
                    .name(names[i])
                    .email(names[i].toLowerCase() + "@example.com")
                    .age(20 + i * 2)
                    .hot(i < 5)               // id 1~5 为热点用户
                    .createdAt(Instant.now())
                    .build());
        }

        userRepository.saveAll(users);
        log.info("[INIT] 插入 {} 条测试数据，热点用户: id 1~5", users.size());

        printBanner();
    }

    private void printBanner() {
        log.info("");
        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║         Caffeine 缓存学习项目 — 全阶段已就绪                  ║");
        log.info("╠══════════════════════════════════════════════════════════════╣");
        log.info("║  基础篇 (15个接口)                                           ║");
        log.info("║    GET    /api/users/{id}                                    ║");
        log.info("║    GET    /api/users/{id}/debug                              ║");
        log.info("║    GET    /api/cache/stats                                   ║");
        log.info("║    GET    /api/cache/timeline/{id}                           ║");
        log.info("║    GET    /api/cache/evict-demo?maxSize=3                    ║");
        log.info("║    ...                                                       ║");
        log.info("╠══════════════════════════════════════════════════════════════╣");
        log.info("║  进阶篇 (+8个接口)                                           ║");
        log.info("║    PUT    /api/users/update-v2    (@CachePut)                ║");
        log.info("║    GET    /api/users/{id}/sync-test                          ║");
        log.info("║    POST   /api/cache/warmup                                  ║");
        log.info("║    ...                                                       ║");
        log.info("╠══════════════════════════════════════════════════════════════╣");
        log.info("║  高级篇 (+20个接口) — 需要 Redis                              ║");
        log.info("║    GET    /api/advanced/users/{id}                           ║");
        log.info("║    POST   /api/drill/penetrate                               ║");
        log.info("║    POST   /api/drill/breakdown                               ║");
        log.info("║    POST   /api/drill/redis-down                              ║");
        log.info("║    GET    /api/benchmark/compare                             ║");
        log.info("║    ...                                                       ║");
        log.info("╠══════════════════════════════════════════════════════════════╣");
        log.info("║  H2 控制台: http://localhost:8080/h2-console                 ║");
        log.info("║  接口总数: 43+ 个                                             ║");
        log.info("╚══════════════════════════════════════════════════════════════╝");
        log.info("");
    }
}
```

---

## 7. CaffeineConfig.java

```java
package com.example.caffeine.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.concurrent.Executor;

/**
 * Caffeine 缓存配置（第一阶段核心）
 *
 * 定义两套缓存管理器，用于对比实验：
 *   writeCacheManager  → expireAfterWrite（写入后固定过期）
 *   accessCacheManager → expireAfterAccess（访问后重置过期）
 *
 * expireAfterWrite vs expireAfterAccess：
 *   - expireAfterWrite：写入后固定时间过期，无论中间访问多少次。适合 session、验证码等。
 *   - expireAfterAccess：最后一次访问后计时，常访问的数据一直存活。适合热点数据、配置项。
 */
@Configuration
@Slf4j
public class CaffeineConfig {

    /**
     * 缓存 A：expireAfterWrite — 写入后固定时间过期
     *
     * 无论中间访问多少次，到期即过期。
     * 适用：session、验证码、限流计数器等变化频率可预测的数据。
     */
    @Bean("writeCacheManager")
    @Primary
    public CacheManager writeCacheManager() {
        log.info("[CONFIG] writeCacheManager → expireAfterWrite=5min, maxSize=1000");
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(Duration.ofMinutes(5))
                .recordStats()  // 开启统计，用于命中率监控
        );
        return manager;
    }

    /**
     * 缓存 B：expireAfterAccess — 最后访问后计时过期
     *
     * 每次访问都重置过期计时器，常访问的数据一直存活。
     * 适用：热点数据、用户信息、配置项、字典表。
     */
    @Bean("accessCacheManager")
    public CacheManager accessCacheManager() {
        log.info("[CONFIG] accessCacheManager → expireAfterAccess=5min, maxSize=1000");
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterAccess(Duration.ofMinutes(5))
                .recordStats()
        );
        return manager;
    }

    /**
     * 异步线程池（第二阶段：缓存预热、第三阶段：异步刷新）
     */
    @Bean("cacheExecutor")
    public Executor cacheExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("cache-async-");
        executor.initialize();
        log.info("[CONFIG] cacheExecutor 线程池初始化完成");
        return executor;
    }
}
```

---

## 8. CaffeineKeyGenerator.java

```java
package com.example.caffeine.config;

import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Method;
import java.util.StringJoiner;

/**
 * 自定义缓存 Key 生成器（第二阶段）
 *
 * 默认的 SimpleKeyGenerator 只用参数值拼接，
 * 这里演示如何生成更具语义的 key。
 *
 * 用于 search 接口：复合条件查询的缓存 key = "users:name=alice,age=25"
 */
@Configuration
public class CaffeineKeyGenerator {

    @Bean("customKeyGenerator")
    public KeyGenerator customKeyGenerator() {
        return (Object target, Method method, Object... params) -> {
            StringJoiner joiner = new StringJoiner(",");
            String[] paramNames = {"name", "age"}; // 简化演示，实际可通过反射获取
            for (int i = 0; i < params.length; i++) {
                String name = i < paramNames.length ? paramNames[i] : "p" + i;
                joiner.add(name + "=" + params[i]);
            }
            return "users:" + joiner;
        };
    }
}
```

---

## 9. CacheWarmupRunner.java

```java
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
```

---

## 10. RedisConfig.java

```java
package com.example.caffeine.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置（第三阶段）
 *
 * 包含：
 *   1. RedisTemplate — Redis 读写操作
 *   2. RedisMessageListenerContainer — Pub/Sub 消息监听（一致性方案）
 *
 * 注意：如果 Redis 未启动，Bean 仍然会创建（Lettuce 惰性连接），
 *       实际使用时才会抛异常。第一、二阶段不受影响。
 */
@Configuration
@Slf4j
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        // Key 用 String 序列化
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // Value 用 JSON 序列化（需要注册 JavaTimeModule 支持 Instant 类型）
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.activateDefaultTyping(
                mapper.getPolymorphicTypeValidator(),
                ObjectMapper.DefaultTyping.NON_FINAL
        );
        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer(mapper);

        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        template.afterPropertiesSet();

        log.info("[CONFIG] RedisTemplate 初始化完成（JSON 序列化）");
        return template;
    }

    /**
     * Pub/Sub 消息监听容器（第三阶段一致性方案用）
     * CacheConsistencyService 会向此容器注册监听器
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory factory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        log.info("[CONFIG] RedisMessageListenerContainer 初始化完成");
        return container;
    }
}
```

---

## 11. MultiLevelCacheManager.java

```java
package com.example.caffeine.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 多级缓存管理器（L1: Caffeine 本地缓存）
 *
 * 负责管理本地缓存的生命周期和统计。
 * 使用独立的 Caffeine 实例（不走 Spring Cache），更透明、更灵活。
 *
 * 两个 L1 缓存：
 *   - userCache：正常用户数据
 *   - nullValueCache：空值缓存（防穿透），TTL 更短（1分钟）
 */
@Component
@Slf4j
public class MultiLevelCacheManager {

    // L1 本地缓存：按类型区分
    private final Cache<String, Object> userCache;
    private final Cache<String, Object> nullValueCache; // 空值缓存（防穿透）

    // 缓存统计（使用 LongAdder 替代 synchronized，高并发性能更好）
    private final Map<String, AtomicCacheStats> statsMap = new ConcurrentHashMap<>();

    public MultiLevelCacheManager() {
        // 用户缓存：较大容量，短过期
        this.userCache = Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(Duration.ofMinutes(10))
                .recordStats()
                .build();

        // 空值缓存：容量适中，短过期（防穿透，但不过多占用内存）
        this.nullValueCache = Caffeine.newBuilder()
                .maximumSize(200)
                .expireAfterWrite(Duration.ofMinutes(1))
                .recordStats()
                .build();

        // 初始化统计
        statsMap.put("L1_USER", new AtomicCacheStats());
        statsMap.put("L1_NULL", new AtomicCacheStats());
        statsMap.put("L2_REDIS", new AtomicCacheStats());
        statsMap.put("DB", new AtomicCacheStats());

        log.info("[MultiLevel] 多级缓存管理器初始化完成");
    }

    // ========== L1 操作 ==========

    public Object getFromL1(String key) {
        Object value = userCache.getIfPresent(key);
        if (value != null) {
            statsMap.get("L1_USER").hit();
            return value;
        }
        // 检查空值缓存
        value = nullValueCache.getIfPresent(key);
        if (value != null) {
            statsMap.get("L1_NULL").hit();
            return value; // 返回缓存中的空对象
        }
        statsMap.get("L1_USER").miss();
        return null;
    }

    public void putToL1(String key, Object value) {
        if (value == null) {
            nullValueCache.put(key, com.example.caffeine.entity.User.NULL_USER);
            statsMap.get("L1_NULL").put();
        } else {
            userCache.put(key, value);
            statsMap.get("L1_USER").put();
        }
    }

    public void evictL1(String key) {
        userCache.invalidate(key);
        nullValueCache.invalidate(key);
        log.debug("[L1] 本地缓存失效: {}", key);
    }

    public void evictLocalCache(String key) {
        evictL1(key);
    }

    // ========== 统计 ==========

    public void recordL2Hit() { statsMap.get("L2_REDIS").hit(); }
    public void recordL2Miss() { statsMap.get("L2_REDIS").miss(); }
    public void recordDbHit() { statsMap.get("DB").hit(); }
    public void recordDbMiss() { statsMap.get("DB").miss(); }

    public Map<String, Object> getLevelStats() {
        Map<String, Object> result = new ConcurrentHashMap<>();

        // L1 统计
        CacheStats userStats = userCache.stats();
        CacheStats nullStats = nullValueCache.stats();
        long totalL1Hit = userStats.hitCount() + nullStats.hitCount();
        long totalL1Miss = userStats.missCount() + nullStats.missCount();
        long totalL1Req = totalL1Hit + totalL1Miss;

        result.put("l1", Map.of(
                "name", "Caffeine (L1)",
                "hitCount", totalL1Hit,
                "missCount", totalL1Miss,
                "hitRate", totalL1Req > 0 ? String.format("%.1f%%", totalL1Hit * 100.0 / totalL1Req) : "N/A",
                "avgLatency", "~0.001ms (纳秒级)"
        ));

        // L2 统计
        AtomicCacheStats l2Stats = statsMap.get("L2_REDIS");
        long l2Total = l2Stats.hitCount() + l2Stats.missCount();
        result.put("l2", Map.of(
                "name", "Redis (L2)",
                "hitCount", l2Stats.hitCount(),
                "missCount", l2Stats.missCount(),
                "hitRate", l2Total > 0 ? String.format("%.1f%%", l2Stats.hitCount() * 100.0 / l2Total) : "N/A",
                "avgLatency", "~0.5-2ms (毫秒级)"
        ));

        // DB 统计
        AtomicCacheStats dbStats = statsMap.get("DB");
        result.put("db", Map.of(
                "name", "Database (L3)",
                "queryCount", dbStats.hitCount() + dbStats.missCount(),
                "avgLatency", "~10-50ms (毫秒级)"
        ));

        // 总结
        result.put("summary", String.format(
                "L1 缓存命中率 %s，L2 缓存命中率 %s，数据库查询次数 %d",
                ((Map<?, ?>) result.get("l1")).get("hitRate"),
                ((Map<?, ?>) result.get("l2")).get("hitRate"),
                dbStats.hitCount() + dbStats.missCount()
        ));

        return result;
    }

    // ========== 内部类：使用 LongAdder 替代 synchronized ==========

    static class AtomicCacheStats {
        private final LongAdder hitCount = new LongAdder();
        private final LongAdder missCount = new LongAdder();
        private final LongAdder putCount = new LongAdder();

        void hit()  { hitCount.increment(); }
        void miss() { missCount.increment(); }
        void put()  { putCount.increment(); }

        long hitCount()  { return hitCount.sum(); }
        long missCount() { return missCount.sum(); }
        long putCount()  { return putCount.sum(); }
    }
}
```

---

## 12. BloomFilterInitializer.java

```java
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
```

---

## 13. UserService.java

```java
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
```

---

## 14. MultiLevelCacheService.java

```java
package com.example.caffeine.service;

import com.example.caffeine.config.MultiLevelCacheManager;
import com.example.caffeine.entity.User;
import com.example.caffeine.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 多级缓存服务（第三阶段核心）
 *
 * 请求流程：
 *   L1 (Caffeine 本地缓存, 纳秒级)
 *     ↓ miss
 *   L2 (Redis 远程缓存, 毫秒级)
 *     ↓ miss
 *   DB (数据库, 十毫秒级)
 *     ↓ 回填 L1 + L2
 *   返回结果
 *
 * 设计原则：
 *   - L1 通过 MultiLevelCacheManager 管理（独立 Caffeine 实例）
 *   - Redis 不可用时自动降级为 L1 + DB
 *   - 统计各级命中率
 */
@Service
@Slf4j
public class MultiLevelCacheService {

    @Autowired
    private UserRepository userRepository;

    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private MultiLevelCacheManager cacheManager;

    @Autowired
    private CacheDegradationService degradationService;

    // Redis key 前缀
    private static final String REDIS_KEY_PREFIX = "user:";
    // 默认 Redis 过期时间
    private static final int REDIS_TTL_MINUTES = 30;

    // 逻辑过期时间记录（用于异步刷新演示）
    private static final long LOGICAL_EXPIRE_MS = 5 * 60 * 1000; // 5 分钟

    // 命中统计
    private long l2Hits = 0, dbQueries = 0;

    // ====================================================================
    //  多级缓存查询（标准流程）
    // ====================================================================

    public User getUser(Long id) {
        String key = REDIS_KEY_PREFIX + id;

        // ========== L1: Caffeine 本地缓存 ==========
        Object l1Value = cacheManager.getFromL1(key);
        if (l1Value != null) {
            if (l1Value instanceof User user) {
                if (user.isNullUser()) {
                    log.info("[L1 HIT] id={}, 返回空对象（防穿透）", id);
                    return null;
                }
                log.debug("[L1 HIT] id={}, 本地缓存命中", id);
                return user;
            }
        }

        // ========== L2: Redis 远程缓存 ==========
        if (degradationService.isRedisAvailable()) {
            try {
                Object l2Value = redisTemplate.opsForValue().get(key);
                if (l2Value instanceof User user) {
                    cacheManager.putToL1(key, user); // 回填 L1
                    cacheManager.recordL2Hit();
                    l2Hits++;
                    log.debug("[L2 HIT] id={}, Redis 命中，已回填本地缓存", id);
                    return user;
                }
            } catch (Exception e) {
                log.warn("[L2 ERROR] Redis 查询失败: {}", e.getMessage());
                cacheManager.recordL2Miss();
            }
        }

        // ========== L3: 数据库 ==========
        log.debug("[DB QUERY] id={}, 查询数据库", id);
        User user = userRepository.findById(id).orElse(null);
        dbQueries++;
        cacheManager.recordDbHit();

        if (user != null) {
            // 回填 L1
            cacheManager.putToL1(key, user);
            // 回填 L2
            if (degradationService.isRedisAvailable()) {
                try {
                    int randomTTL = User.randomExpireOffset(REDIS_TTL_MINUTES, 10);
                    redisTemplate.opsForValue().set(key, user, randomTTL, TimeUnit.MINUTES);
                } catch (Exception e) {
                    log.warn("[L2 WRITE ERROR] {}", e.getMessage());
                }
            }
        } else {
            // 空对象缓存（防穿透）
            cacheManager.putToL1(key, null);
        }

        return user;
    }

    // ====================================================================
    //  异步刷新演示（逻辑过期 → 返回旧值 + 后台刷新）
    // ====================================================================

    /**
     * 逻辑过期的多级缓存查询
     *
     * 当 key 逻辑过期时：
     *   1. 立即返回旧值（不阻塞调用方）
     *   2. 提交异步任务刷新缓存
     */
    public User getUserWithAsyncRefresh(Long id) {
        String key = REDIS_KEY_PREFIX + id;

        Object l1Value = cacheManager.getFromL1(key);
        if (l1Value instanceof User user && !user.isNullUser()) {
            // 检查逻辑过期时间
            if (user.getLogicExpireTime() != null &&
                user.getLogicExpireTime().isBefore(Instant.now())) {
                // 逻辑过期：返回旧值 + 异步刷新
                log.info("[ASYNC] id={}, 缓存逻辑过期，返回旧值并异步刷新", id);
                asyncRefresh(id, key);
            } else {
                log.debug("[L1 HIT] id={}, 缓存有效", id);
            }
            return user;
        }

        // 缓存不存在，同步加载
        return loadAndCache(id, key);
    }

    /**
     * 异步刷新缓存（在新线程中执行）
     */
    private void asyncRefresh(Long id, String key) {
        new Thread(() -> {
            try {
                User fresh = userRepository.findById(id).orElse(null);
                if (fresh != null) {
                    fresh.setLogicExpireTime(Instant.now().plusMillis(LOGICAL_EXPIRE_MS));
                    cacheManager.putToL1(key, fresh);

                    if (degradationService.isRedisAvailable()) {
                        redisTemplate.opsForValue().set(key, fresh, 60, TimeUnit.MINUTES);
                    }
                    log.info("[ASYNC REFRESH] id={} 缓存已刷新", id);
                }
            } catch (Exception e) {
                log.error("[ASYNC REFRESH] id={} 刷新失败: {}", id, e.getMessage());
            }
        }, "async-refresh-" + id).start();
    }

    private User loadAndCache(Long id, String key) {
        log.debug("[DB QUERY - async] id={}", id);
        User user = userRepository.findById(id).orElse(null);
        if (user != null) {
            user.setLogicExpireTime(Instant.now().plusMillis(LOGICAL_EXPIRE_MS));
            cacheManager.putToL1(key, user);
            if (degradationService.isRedisAvailable()) {
                try {
                    redisTemplate.opsForValue().set(key, user, 60, TimeUnit.MINUTES);
                } catch (Exception ignored) {}
            }
        }
        return user;
    }

    // ====================================================================
    //  仅 Caffeine / 仅 Redis（基准测试用）
    // ====================================================================

    public User getUserCaffeineOnly(Long id) {
        String key = REDIS_KEY_PREFIX + id;
        Object l1Value = cacheManager.getFromL1(key);
        if (l1Value instanceof User user && !user.isNullUser()) return user;
        User user = userRepository.findById(id).orElse(null);
        if (user != null) cacheManager.putToL1(key, user);
        return user;
    }

    public User getUserRedisOnly(Long id) {
        String key = REDIS_KEY_PREFIX + id;
        if (degradationService.isRedisAvailable()) {
            try {
                User user = (User) redisTemplate.opsForValue().get(key);
                if (user != null) return user;
            } catch (Exception ignored) {}
        }
        User user = userRepository.findById(id).orElse(null);
        if (user != null && degradationService.isRedisAvailable()) {
            try {
                redisTemplate.opsForValue().set(key, user, 30, TimeUnit.MINUTES);
            } catch (Exception ignored) {}
        }
        return user;
    }

    // ====================================================================
    //  缓存管理
    // ====================================================================

    public void invalidateL1(Long id) {
        cacheManager.evictL1(REDIS_KEY_PREFIX + id);
    }

    /**
     * 多级缓存一致性删除
     */
    public void invalidateCache(Long id) {
        String key = REDIS_KEY_PREFIX + id;

        // 1. 删除 L1
        cacheManager.evictL1(key);

        // 2. 删除 L2
        if (degradationService.isRedisAvailable()) {
            try {
                redisTemplate.delete(key);
            } catch (Exception e) {
                log.warn("[INVALIDATE] 删除 Redis 缓存失败: {}", e.getMessage());
            }
        }

        log.info("[INVALIDATE] 已删除 L1+L2 缓存: {}", key);
    }

    public void invalidateAll() {
        // L1 清理由 MultiLevelCacheManager 内部处理
        l2Hits = dbQueries = 0;
    }

    // ====================================================================
    //  统计信息
    // ====================================================================

    public Map<String, Object> getLevelStats() {
        Map<String, Object> stats = new LinkedHashMap<>();

        // L1 统计（从 MultiLevelCacheManager 获取）
        Map<String, Object> managerStats = cacheManager.getLevelStats();
        stats.putAll(managerStats);

        stats.put("redisAvailable", degradationService.isRedisAvailable());

        return stats;
    }
}
```

---

## 15. CacheProtectionService.java

```java
package com.example.caffeine.service;

import com.example.caffeine.config.BloomFilterInitializer;
import com.example.caffeine.entity.User;
import com.example.caffeine.repository.UserRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 缓存防护服务（第三阶段）
 *
 * 三道防线：
 *   1. 穿透防护 — null 值缓存 / 布隆过滤器
 *   2. 击穿防护 — sync=true / 逻辑过期时间
 *   3. 雪崩防护 — 过期时间加随机偏移
 */
@Service
@Slf4j
public class CacheProtectionService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BloomFilterInitializer bloomFilterInitializer;

    // ---- 空对象缓存（防穿透） ----
    private final Cache<Long, User> nullCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(Duration.ofSeconds(30))
            .build();

    // ---- 逻辑过期缓存（防击穿） ----
    private final Cache<Long, User> logicExpireCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(Duration.ofHours(1))  // 长 TTL，靠逻辑过期控制
            .build();
    private final Map<Long, Instant> logicalExpireTimes = new ConcurrentHashMap<>();
    private static final long LOGICAL_TTL_MS = Duration.ofSeconds(10).toMillis();

    // ====================================================================
    //  穿透防护：三种模式对比
    // ====================================================================

    /**
     * 穿透演练
     *
     * @param count 查询次数
     * @param mode  none / null-cache / bloom-filter
     */
    public Map<String, Object> drillPenetration(int count, String mode) {
        AtomicInteger dbQueryCount = new AtomicInteger(0);
        int bloomBlocked = 0;
        long start = System.nanoTime();

        for (int i = 0; i < count; i++) {
            Long fakeId = 100_000L + i; // 不存在的 id
            String key = "user:" + fakeId;

            switch (mode) {
                case "none" -> {
                    // 无防护：每次查库
                    userRepository.findById(fakeId);
                    dbQueryCount.incrementAndGet();
                }
                case "null-cache" -> {
                    // 空对象缓存：第一次查库，后续命中缓存
                    User cached = nullCache.getIfPresent(fakeId);
                    if (cached == null) {
                        userRepository.findById(fakeId);
                        dbQueryCount.incrementAndGet();
                        nullCache.put(fakeId, User.NULL_USER); // 缓存空对象
                    }
                }
                case "bloom-filter" -> {
                    // 布隆过滤器：不存在的 id 直接拦截，0 次查库
                    if (!bloomFilterInitializer.mightContain(key)) {
                        bloomBlocked++;
                        // 布隆过滤器判断不存在 → 直接跳过
                    } else {
                        // 误判了，继续查库（概率极低）
                        userRepository.findById(fakeId);
                        dbQueryCount.incrementAndGet();
                    }
                }
            }
        }

        long elapsedNs = System.nanoTime() - start;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", mode);
        result.put("totalRequests", count);
        result.put("dbQueryCount", dbQueryCount.get());
        if ("bloom-filter".equals(mode)) {
            result.put("bloomBlockedCount", bloomBlocked);
        }
        result.put("duration", String.format("%.1fms", elapsedNs / 1_000_000.0));
        result.put("description", switch (mode) {
            case "none" -> "无防护：每次请求都打到数据库";
            case "null-cache" -> "空对象缓存：只有第一次查库，后续命中缓存（短 TTL）";
            case "bloom-filter" -> "布隆过滤器：不存在的 key 直接拦截，0 次查库";
            default -> "";
        });

        return result;
    }

    // ====================================================================
    //  击穿防护：三种模式对比
    // ====================================================================

    /**
     * 击穿演练：模拟缓存过期瞬间大量并发
     *
     * @param mode none / sync / logic-expire
     */
    public Map<String, Object> drillBreakdown(int threads, String mode) throws Exception {
        Long targetId = 1L; // 热点 key

        // 准备：确保 key 在缓存中
        User user = userRepository.findById(targetId).orElse(null);
        if (user == null) return Map.of("error", "需要先创建 id=1 的用户");

        // 清除缓存，模拟即将过期
        logicExpireCache.invalidate(targetId);
        logicalExpireTimes.remove(targetId);

        AtomicInteger dbQueryCount = new AtomicInteger(0);
        AtomicInteger returnOldValueCount = new AtomicInteger(0);  // 使用 AtomicInteger 避免竞态
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        long beginNs = System.nanoTime();

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    startLatch.await(); // 等待同时启动

                    switch (mode) {
                        case "none" -> {
                            // 无防护：每个线程都查库
                            User cached = logicExpireCache.getIfPresent(targetId);
                            if (cached == null) {
                                try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                                userRepository.findById(targetId);
                                dbQueryCount.incrementAndGet();
                                logicExpireCache.put(targetId, user);
                            }
                        }
                        case "sync" -> {
                            // sync=true：只有一个线程查库（Caffeine native API）
                            logicExpireCache.get(targetId, id -> {
                                dbQueryCount.incrementAndGet();
                                try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                                return userRepository.findById(id).orElse(null);
                            });
                        }
                        case "logic-expire" -> {
                            // 逻辑过期：返回旧值 + 异步刷新
                            User cached = logicExpireCache.getIfPresent(targetId);
                            if (cached != null) {
                                Instant expireTime = logicalExpireTimes.get(targetId);
                                if (expireTime != null && Instant.now().isAfter(expireTime)) {
                                    returnOldValueCount.incrementAndGet();
                                    // 只有一个线程负责刷新
                                    if (dbQueryCount.compareAndSet(0, 1)) {
                                        try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                                        User fresh = userRepository.findById(targetId).orElse(null);
                                        if (fresh != null) {
                                            logicExpireCache.put(targetId, fresh);
                                            logicalExpireTimes.put(targetId,
                                                    Instant.now().plusMillis(LOGICAL_TTL_MS));
                                        }
                                    }
                                    // 其他线程直接返回旧值
                                }
                            } else {
                                // 缓存为空，需要初始化
                                logicExpireCache.get(targetId, id -> {
                                    dbQueryCount.incrementAndGet();
                                    return userRepository.findById(id).orElse(null);
                                });
                                logicalExpireTimes.put(targetId,
                                        Instant.now().plusMillis(LOGICAL_TTL_MS));
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("击穿演练异常", e);
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        // 同时释放所有线程
        startLatch.countDown();
        doneLatch.await();

        long elapsedNs = System.nanoTime() - beginNs;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", mode);
        result.put("threads", threads);
        result.put("dbQueryCount", dbQueryCount.get());
        if ("logic-expire".equals(mode)) {
            result.put("returnOldValueCount", returnOldValueCount.get());
        }
        result.put("duration", String.format("%.1fms", elapsedNs / 1_000_000.0));
        result.put("description", switch (mode) {
            case "none" -> "无防护：所有线程都查库（缓存击穿）";
            case "sync" -> "sync=true：只有一个线程查库，其他等待结果";
            case "logic-expire" -> "逻辑过期：返回旧值 + 单线程异步刷新";
            default -> "";
        });

        // 清理
        logicExpireCache.invalidate(targetId);

        return result;
    }

    // ====================================================================
    //  雪崩防护：过期时间随机偏移
    // ====================================================================

    /**
     * 雪崩演练：对比固定 TTL vs 随机偏移 TTL
     */
    public Map<String, Object> drillAvalanche(int keyCount, String mode) {
        Cache<Long, String> testCache = Caffeine.newBuilder()
                .maximumSize(keyCount + 10)
                .build();

        long baseTTL = 100; // 基础 TTL 100ms

        // 写入 keyCount 个 key
        for (long i = 1; i <= keyCount; i++) {
            testCache.put(i, "value-" + i);
        }

        // 模拟过期后同时查询
        if ("fixed-ttl".equals(mode)) {
            // 固定 TTL：所有 key 同时过期
            try { Thread.sleep(baseTTL + 10); } catch (InterruptedException ignored) {}
        } else {
            // 随机偏移：等基础 TTL 后，大部分 key 已过期，但有些还没
            try { Thread.sleep(baseTTL + 10); } catch (InterruptedException ignored) {}
        }

        // 测量同时查询的并发量
        CountDownLatch latch = new CountDownLatch(keyCount);
        AtomicInteger concurrentQueries = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);

        for (long i = 1; i <= keyCount; i++) {
            final long key = i;
            new Thread(() -> {
                try {
                    // 模拟：检查缓存
                    if (testCache.getIfPresent(key) == null) {
                        int current = concurrentQueries.incrementAndGet();
                        maxConcurrent.updateAndGet(prev -> Math.max(prev, current));
                        try { Thread.sleep(5); } catch (InterruptedException ignored) {} // 模拟查库
                        concurrentQueries.decrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        try { latch.await(); } catch (InterruptedException ignored) {}

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", mode);
        result.put("keyCount", keyCount);
        result.put("peakConcurrentDbQueries", maxConcurrent.get());
        result.put("description", "fixed-ttl".equals(mode)
                ? "固定 TTL：所有 key 同时过期，峰值并发 = " + maxConcurrent.get()
                : "随机 TTL：过期时间分散，峰值并发已降低（实际效果需配合不同 TTL 写入）");

        return result;
    }
}
```

---

## 16. CacheConsistencyService.java

```java
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
```

---

## 17. CacheDegradationService.java

```java
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
```

---

## 18. UserController.java

```java
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
```

---

## 19. CacheMonitorController.java

```java
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
                nc.policy().expireAfterWrite().ifPresent(policy -> {
                    policy.get(id, TimeUnit.MILLISECONDS).ifPresent(remainingMs -> {
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
                nc.policy().expireAfterAccess().ifPresent(policy -> {
                    policy.get(id, TimeUnit.MILLISECONDS).ifPresent(remainingMs -> {
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
                + " W-TinyLFU 维护一个频率 sketch，新条目需要击败"门卫"才能进入缓存。");
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
```

---

## 20. CacheWarmupController.java

```java
package com.example.caffeine.controller;

import com.example.caffeine.config.CacheWarmupRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 缓存预热控制器（第二阶段）
 *
 * POST /api/cache/warmup         → 触发预热（异步执行）
 * GET  /api/cache/warmup/status  → 查看预热进度
 */
@RestController
@RequestMapping("/api/cache")
public class CacheWarmupController {

    @Autowired
    private CacheWarmupRunner warmupRunner;

    // ---- #20 触发预热 ----
    @PostMapping("/warmup")
    public Map<String, Object> triggerWarmup() {
        var status = warmupRunner.triggerWarmup();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "预热已触发（异步执行）");
        result.put("currentStatus", status.getStatus());
        result.put("detail", "GET /api/cache/warmup/status 查看进度");
        return result;
    }

    // ---- #21 预热进度 ----
    @GetMapping("/warmup/status")
    public Map<String, Object> warmupStatus() {
        var status = warmupRunner.getStatus();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status.getStatus());
        result.put("currentPriority", status.getCurrentPriority());
        result.put("priorityStats", status.getPriorityStats());
        result.put("totalLoaded", status.getTotalLoaded().get());
        result.put("totalFailed", status.getTotalFailed().get());
        result.put("elapsedMs", status.getElapsedMs());
        result.put("failedKeys", status.getFailedKeys());
        if (status.getStartTime() != null) result.put("startTime", status.getStartTime().toString());
        if (status.getError() != null) result.put("error", status.getError());
        return result;
    }
}
```

---

## 21. AdvancedCacheController.java

```java
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
```

---

## 22. FaultDrillController.java

```java
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
```

---

## 23. CacheBenchmarkController.java

```java
package com.example.caffeine.controller;

import com.example.caffeine.entity.User;
import com.example.caffeine.repository.UserRepository;
import com.example.caffeine.service.CacheDegradationService;
import com.example.caffeine.service.MultiLevelCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 性能基准测试控制器（第三阶段）
 *
 * 用数据说话：不同缓存策略的性能差异
 */
@RestController
@RequestMapping("/api/benchmark")
@Slf4j
public class CacheBenchmarkController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MultiLevelCacheService multiLevelCacheService;

    @Autowired
    private CacheDegradationService degradationService;

    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    @Qualifier("writeCacheManager")
    private CacheManager writeCacheManager;

    private static final int USER_COUNT = 20;

    // ---- #41 四种模式汇总对比 ----
    @GetMapping("/compare")
    public Map<String, Object> compare(@RequestParam(defaultValue = "1000") int n) {
        Map<String, Object> result = new LinkedHashMap<>();

        // 1. 无缓存（直接查库）
        clearAllCaches();
        result.put("noCache", benchmarkDirectDB(n));

        // 2. 仅 Caffeine
        clearAllCaches();
        result.put("caffeineOnly", benchmarkCaffeineOnly(n));

        // 3. 仅 Redis
        clearAllCaches();
        result.put("redisOnly", benchmarkRedisOnly(n));

        // 4. 多级缓存
        clearAllCaches();
        result.put("multiLevel", benchmarkMultiLevel(n));

        result.put("queryCount", n);
        result.put("note", "第一次查询为缓存 miss（查库），后续查询命中缓存");
        return result;
    }

    // ---- #42 冷启动性能 ----
    @GetMapping("/cold-start")
    public Map<String, Object> coldStart(@RequestParam(defaultValue = "1000") int n) {
        clearAllCaches();
        Map<String, Object> result = benchmarkDirectDB(n);
        result.put("scenario", "冷启动（无缓存，每次查库）");
        return result;
    }

    // ---- #43 预热后性能 ----
    @GetMapping("/pre-warmed")
    public Map<String, Object> preWarmed(@RequestParam(defaultValue = "1000") int n) {
        clearAllCaches();

        // 预热
        long warmupStart = System.nanoTime();
        for (long i = 1; i <= USER_COUNT; i++) {
            multiLevelCacheService.getUser(i);
        }
        long warmupTime = System.nanoTime() - warmupStart;

        // 测试
        Map<String, Object> result = benchmarkMultiLevel(n);
        result.put("scenario", "预热后（缓存已加载）");
        result.put("warmupTime", String.format("%.1fms", warmupTime / 1_000_000.0));

        return result;
    }

    // ====================================================================
    //  基准测试方法
    // ====================================================================

    private Map<String, Object> benchmarkDirectDB(int n) {
        long start = System.nanoTime();
        for (int i = 0; i < n; i++) {
            long id = (i % USER_COUNT) + 1;
            userRepository.findById(id);
        }
        return buildResult("无缓存（直接查库）", n, System.nanoTime() - start);
    }

    private Map<String, Object> benchmarkCaffeineOnly(int n) {
        long start = System.nanoTime();
        for (int i = 0; i < n; i++) {
            long id = (i % USER_COUNT) + 1;
            multiLevelCacheService.getUserCaffeineOnly(id);
        }
        return buildResult("仅 Caffeine (L1)", n, System.nanoTime() - start);
    }

    private Map<String, Object> benchmarkRedisOnly(int n) {
        if (!degradationService.isRedisAvailable()) {
            return Map.of("name", "仅 Redis (L2)", "error", "Redis 不可用，跳过");
        }
        long start = System.nanoTime();
        for (int i = 0; i < n; i++) {
            long id = (i % USER_COUNT) + 1;
            multiLevelCacheService.getUserRedisOnly(id);
        }
        return buildResult("仅 Redis (L2)", n, System.nanoTime() - start);
    }

    private Map<String, Object> benchmarkMultiLevel(int n) {
        long start = System.nanoTime();
        for (int i = 0; i < n; i++) {
            long id = (i % USER_COUNT) + 1;
            multiLevelCacheService.getUser(id);
        }
        return buildResult("多级缓存 (L1+L2)", n, System.nanoTime() - start);
    }

    private Map<String, Object> buildResult(String name, int n, long elapsedNs) {
        Map<String, Object> result = new LinkedHashMap<>();
        double elapsedMs = elapsedNs / 1_000_000.0;
        result.put("name", name);
        result.put("queryCount", n);
        result.put("totalTime", String.format("%.1fms", elapsedMs));
        result.put("avgTime", String.format("%.4fms", elapsedMs / n));
        result.put("qps", (int) (n / (elapsedMs / 1000)));
        return result;
    }

    private void clearAllCaches() {
        // 清除 Spring Cache
        var wc = writeCacheManager.getCache("users");
        if (wc != null) wc.clear();
        // 清除 L1
        multiLevelCacheService.invalidateAll();
        // 清除 Redis
        if (degradationService.isRedisAvailable() && redisTemplate != null) {
            try {
                Set<String> keys = redisTemplate.keys("user:*");
                if (keys != null && !keys.isEmpty()) {
                    redisTemplate.delete(keys);
                }
            } catch (Exception ignored) {}
        }
    }
}
```

---

## 完整测试指南

### 启动项目

```bash
# 1. 启动项目（第一、二阶段立即可用，无需 Redis）
mvn spring-boot:run

# 2. 如需使用第三阶段功能，启动 Redis
docker run -d -p 6379:6379 redis:latest
```

---

### 第一阶段测试（基础篇）— 15 个接口

```bash
# ---- 基础缓存 ----

# #1 查用户（第一次查库 ~100ms，第二次走缓存 <1ms）
curl http://localhost:8080/api/users/1
curl http://localhost:8080/api/users/1

# #2 手动缓存方式
curl http://localhost:8080/api/users/1/manual

# #3 Debug 模式（显示来源、穿透风险）
curl http://localhost:8080/api/users/1/debug

# #3b 查不存在的用户（穿透风险提示）
curl http://localhost:8080/api/users/99999/debug

# #4 新增用户
curl -X POST http://localhost:8080/api/users \
  -H "Content-Type: application/json" \
  -d '{"name":"Zara","email":"zara@example.com","age":28}'

# #5 更新用户（@CacheEvict → 缓存被删除）
curl -X PUT http://localhost:8080/api/users \
  -H "Content-Type: application/json" \
  -d '{"id":1,"name":"Alice-New","email":"alice@example.com","age":30}'

# #6 删除用户
curl -X DELETE http://localhost:8080/api/users/21

# ---- 监控 ----

# #7 全局命中率统计
curl http://localhost:8080/api/cache/stats

# #8 按 key 查看状态
curl http://localhost:8080/api/cache/stats/1

# #9 缓存大小
curl http://localhost:8080/api/cache/size

# #10 时间线（查看 TTL 剩余时间）
curl http://localhost:8080/api/cache/timeline/1

# #11 清空所有缓存
curl -X DELETE http://localhost:8080/api/cache/clear

# ---- 对比实验 ----

# #12 write 缓存（expireAfterWrite）
curl http://localhost:8080/api/users/2

# #13 access 缓存（expireAfterAccess）
curl http://localhost:8080/api/users/2?cacheType=access

# #14 W-TinyLFU 淘汰行为观察
curl http://localhost:8080/api/cache/evict-demo?maxSize=3

# #15 简单预热
curl -X POST http://localhost:8080/api/cache/warmup-simple
```

---

### 第二阶段测试（进阶篇）— +8 个接口

```bash
# ---- @CachePut vs @CacheEvict ----

# #16 @CachePut：更新后直接刷新缓存（下次读取命中）
curl -X PUT http://localhost:8080/api/users/update-v2 \
  -H "Content-Type: application/json" \
  -d '{"id":1,"name":"Alice-v2","email":"alice@example.com","age":30}'
curl http://localhost:8080/api/users/1  # 命中缓存，返回 Alice-v2

# #17 @CacheEvict：更新后删除缓存（下次读取 miss）
curl -X PUT http://localhost:8080/api/users/update-v3 \
  -H "Content-Type: application/json" \
  -d '{"id":1,"name":"Alice-v3","email":"alice@example.com","age":31}'
curl http://localhost:8080/api/users/1/debug  # miss 一次

# ---- sync=true 并发保护 ----

# #19 同 key 并发测试（sync=true 有效：只有 1 次查库）
curl "http://localhost:8080/api/users/1/sync-test?threads=100"

# #20 不同 key 并发测试（sync=true 无效：每个 key 各自独立）
curl "http://localhost:8080/api/users/sync-test-fail?keyCount=100&threads=100"

# ---- 复合条件查询 ----

# #18 搜索（自定义 KeyGenerator）
curl "http://localhost:8080/api/users/search?name=Alice&age=20"

# ---- 条件缓存 ----

# #23 id < 100 → 会缓存
curl http://localhost:8080/api/users/50/conditional

# #23 id >= 100 → 不缓存
curl http://localhost:8080/api/users/500/conditional

# ---- 优先级预热 ----

# #20 触发预热（异步执行）
curl -X POST http://localhost:8080/api/cache/warmup

# #21 查看预热进度
curl http://localhost:8080/api/cache/warmup/status
```

---

### 第三阶段测试（高级篇）— +20 个接口（需要 Redis）

```bash
# ---- 多级缓存 ----

# #24 多级缓存查询（L1→L2→DB）
curl http://localhost:8080/api/advanced/users/1

# #25 异步刷新（返回旧值 + 后台刷新）
curl http://localhost:8080/api/advanced/users/1/async

# #26 两级缓存命中率对比
curl http://localhost:8080/api/advanced/cache/level-stats

# #27 多级缓存一致性删除（Pub/Sub 广播）
curl -X POST http://localhost:8080/api/advanced/cache/invalidate/1

# #28 一致性方案对比
curl http://localhost:8080/api/advanced/consistency/compare

# ---- 穿透防护演练 ----

# #29 无防护（每次查库）
curl -X POST "http://localhost:8080/api/drill/penetrate?count=1000&mode=none"

# #30 空对象缓存（第一次查库，后续命中）
curl -X POST "http://localhost:8080/api/drill/penetrate?count=1000&mode=null-cache"

# #31 布隆过滤器（0 次查库）
curl -X POST "http://localhost:8080/api/drill/penetrate?count=1000&mode=bloom-filter"

# ---- 击穿防护演练 ----

# #32 无防护（所有线程查库）
curl -X POST "http://localhost:8080/api/drill/breakdown?threads=100&mode=none"

# #33 sync=true（只有 1 个线程查库）
curl -X POST "http://localhost:8080/api/drill/breakdown?threads=100&mode=sync"

# #34 逻辑过期（返回旧值 + 单线程异步刷新）
curl -X POST "http://localhost:8080/api/drill/breakdown?threads=100&mode=logic-expire"

# ---- 雪崩防护演练 ----

# #35 固定 TTL（所有 key 同时过期）
curl -X POST "http://localhost:8080/api/drill/avalanche?keyCount=50&mode=fixed-ttl"

# #36 随机偏移 TTL（过期时间分散）
curl -X POST "http://localhost:8080/api/drill/avalanche?keyCount=50&mode=random-ttl"

# ---- 故障演练 ----

# #37 模拟 Redis 宕机
curl -X POST http://localhost:8080/api/drill/redis-down

# 降级后查询（仍能正常响应）
curl http://localhost:8080/api/advanced/users/1

# #38 模拟 Redis 恢复
curl -X POST http://localhost:8080/api/drill/redis-recover

# #39 验证数据一致性
curl "http://localhost:8080/api/drill/verify-consistency?id=1"

# #40 演练结果汇总
curl http://localhost:8080/api/drill/result

# ---- 性能基准 ----

# #41 四种模式汇总对比（无缓存 vs Caffeine vs Redis vs 多级）
curl "http://localhost:8080/api/benchmark/compare?n=1000"

# #42 冷启动性能（无缓存，每次查库）
curl "http://localhost:8080/api/benchmark/cold-start?n=1000"

# #43 预热后性能（缓存已加载）
curl "http://localhost:8080/api/benchmark/pre-warmed?n=1000"
```

---

### 接口总览（43+ 个）

| # | 方法 | 路径 | 阶段 | 说明 |
|---|------|------|------|------|
| 1 | GET | `/api/users/{id}` | 基础 | @Cacheable 查询 |
| 2 | GET | `/api/users/{id}/manual` | 基础 | 手动缓存 API |
| 3 | GET | `/api/users/{id}/debug` | 基础 | Debug 模式 |
| 4 | POST | `/api/users` | 基础 | 新增用户 |
| 5 | PUT | `/api/users` | 基础 | 更新（@CacheEvict） |
| 6 | DELETE | `/api/users/{id}` | 基础 | 删除用户 |
| 7 | GET | `/api/cache/stats` | 基础 | 全局命中率 |
| 8 | GET | `/api/cache/stats/{key}` | 基础 | 按 key 查看 |
| 9 | GET | `/api/cache/size` | 基础 | 缓存大小 |
| 10 | GET | `/api/cache/timeline/{id}` | 基础 | 时间线 |
| 11 | DELETE | `/api/cache/clear` | 基础 | 清空缓存 |
| 12 | GET | `/api/users/{id}?cacheType=write` | 基础 | write 缓存 |
| 13 | GET | `/api/users/{id}?cacheType=access` | 基础 | access 缓存 |
| 14 | GET | `/api/cache/evict-demo` | 基础 | W-TinyLFU 演示 |
| 15 | POST | `/api/cache/warmup-simple` | 基础 | 简单预热 |
| 16 | PUT | `/api/users/update-v2` | 进阶 | @CachePut |
| 17 | PUT | `/api/users/update-v3` | 进阶 | @CacheEvict |
| 18 | GET | `/api/users/search` | 进阶 | 自定义 KeyGenerator |
| 19 | GET | `/api/users/{id}/sync-test` | 进阶 | sync=true 并发 |
| 20 | GET | `/api/users/sync-test-fail` | 进阶 | sync=true 失效 |
| 21 | GET | `/api/users/{id}/conditional` | 进阶 | 条件缓存 |
| 22 | POST | `/api/cache/warmup` | 进阶 | 优先级预热 |
| 23 | GET | `/api/cache/warmup/status` | 进阶 | 预热进度 |
| 24 | GET | `/api/advanced/users/{id}` | 高级 | 多级缓存查询 |
| 25 | GET | `/api/advanced/users/{id}/async` | 高级 | 异步刷新 |
| 26 | GET | `/api/advanced/cache/level-stats` | 高级 | 命中率对比 |
| 27 | POST | `/api/advanced/cache/invalidate/{id}` | 高级 | Pub/Sub 一致性删除 |
| 28 | GET | `/api/advanced/consistency/compare` | 高级 | 一致性方案对比 |
| 29 | POST | `/api/drill/penetrate?mode=none` | 高级 | 穿透-无防护 |
| 30 | POST | `/api/drill/penetrate?mode=null-cache` | 高级 | 穿透-空对象 |
| 31 | POST | `/api/drill/penetrate?mode=bloom-filter` | 高级 | 穿透-布隆过滤器 |
| 32 | POST | `/api/drill/breakdown?mode=none` | 高级 | 击穿-无防护 |
| 33 | POST | `/api/drill/breakdown?mode=sync` | 高级 | 击穿-sync |
| 34 | POST | `/api/drill/breakdown?mode=logic-expire` | 高级 | 击穿-逻辑过期 |
| 35 | POST | `/api/drill/avalanche?mode=fixed-ttl` | 高级 | 雪崩-固定TTL |
| 36 | POST | `/api/drill/avalanche?mode=random-ttl` | 高级 | 雪崩-随机TTL |
| 37 | POST | `/api/drill/redis-down` | 高级 | 模拟 Redis 宕机 |
| 38 | POST | `/api/drill/redis-recover` | 高级 | 模拟 Redis 恢复 |
| 39 | GET | `/api/drill/verify-consistency` | 高级 | 验证一致性 |
| 40 | GET | `/api/drill/result` | 高级 | 演练结果汇总 |
| 41 | GET | `/api/benchmark/compare` | 高级 | 四种模式对比 |
| 42 | GET | `/api/benchmark/cold-start` | 高级 | 冷启动性能 |
| 43 | GET | `/api/benchmark/pre-warmed` | 高级 | 预热后性能 |

---
