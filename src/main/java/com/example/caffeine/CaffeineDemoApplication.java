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