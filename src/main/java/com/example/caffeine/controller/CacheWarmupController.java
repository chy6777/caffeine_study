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