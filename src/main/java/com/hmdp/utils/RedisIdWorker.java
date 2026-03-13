package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    /**
     * 开始时间戳（示例：2022-01-01 00:00:00 的秒级时间戳）
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;

    /**
     * 序列号占用的位数（32位，支持每天最多 2^32 - 1 个ID）
     */
    private static final int COUNT_BITS = 32;

    private final StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 生成下一个分布式ID
     * @param keyPrefix 业务前缀，用于区分不同业务的ID生成
     * @return 生成的分布式ID
     */
    public long nextId(String keyPrefix) {
        // 1. 生成时间戳部分
        LocalDateTime now = LocalDateTime.now();
        // 获取当前时间的秒级时间戳（UTC时区）
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        // 计算相对时间戳（当前时间 - 起始时间）
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 2. 生成序列号部分
        // 2.1 获取当前日期，精确到天（用于按天重置序列号）
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2 原子性自增，生成当天序列号
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        // 3. 拼接并返回最终ID
        // 时间戳左移 COUNT_BITS 位，空出低32位给序列号
        return timestamp << COUNT_BITS | count;
    }
}