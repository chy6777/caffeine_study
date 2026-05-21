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