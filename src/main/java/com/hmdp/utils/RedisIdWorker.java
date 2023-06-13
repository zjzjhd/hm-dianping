package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * @author zjzjhd
 * @version 1.0
 * @description: TODO
 * @date 2023/2/24 14:52
 */
@Component
public class RedisIdWorker {
    private final static long BEGIN_TIMESTAMP = 1640995200L;
    private final static long COUNT_BITS = 1640995200L;
    private StringRedisTemplate stringRedisTemplate;



    public Long nextId(String keyPrefix) {
        //时间戳
        long nowSecond = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;
        //序列号
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        // 拼接
        return timestamp << COUNT_BITS | count;
    }
}
