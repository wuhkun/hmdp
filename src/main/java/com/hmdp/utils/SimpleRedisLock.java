package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {

    private StringRedisTemplate stringRedisTemplate;
    private String name;
    private static  final String KEY_PREFIX = "lock:";

    private final String ID_PREFIX = UUID.randomUUID().toString(true);
    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }
    @Override
    public boolean tryLock(long timeout) {
        String id = ID_PREFIX + Thread.currentThread().getId();
        Boolean b = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, id , timeout, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(b);
    }

    @Override
    public void unlock() {
        String id = ID_PREFIX + Thread.currentThread().getId();
        String key = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        if(id.equals(key)) {stringRedisTemplate.delete(KEY_PREFIX + name);}

    }
}
