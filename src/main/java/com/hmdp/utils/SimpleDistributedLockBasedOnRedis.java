package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;



/**
 * @author zjzjhd
 * @version 1.0
 * @description: TODO
 * @date 2023/2/25 20:32
 */
@Component
public class SimpleDistributedLockBasedOnRedis implements DistributedLock{
    private String name;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleDistributedLockBasedOnRedis() {

    }

    public SimpleDistributedLockBasedOnRedis(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String ID_PREFIX = UUID.randomUUID() + "-";
    private static final String KEY_PREFIX = "lock:";

    @Override
    public boolean tryLock(long timeoutSeconds) {
        // 线程标识
        String threadName = String.valueOf(Thread.currentThread().getId());
        Boolean isSucceeded = stringRedisTemplate.opsForValue().setIfAbsent(ID_PREFIX + name, threadName, timeoutSeconds, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(isSucceeded);

    }

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("Unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    /**
     * 释放锁
     */
    @Override
    public void unlock() {
        // 调用 Lua 脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,  // SCRIPT
                Collections.singletonList(KEY_PREFIX + name),   // KEY[1]
                ID_PREFIX + Thread.currentThread().getId()    // ARGV[1]
        );
    }

}
