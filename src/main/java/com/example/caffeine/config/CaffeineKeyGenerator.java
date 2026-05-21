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