package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

/**
 * @author zjzjhd
 * @version 1.0
 * @description: TODO
 * @date 2023/2/22 20:41
 */
@Component
public class CacheClientUtils {
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    private final StringRedisTemplate stringRedisTemplate;

    // 构造方法注入
    public CacheClientUtils(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * @description: 将任意 Java 对象序列化为 JSON 存储在 String 类型的 Key 中，并且可以设置 TTL 过期时间
     * @param: [key, value, time, unit]
     * @return: void
     */
    public void setWithPassThrough(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }
    /**
     * @description: 将任意 Java 对象序列化为 JSON 存储在 String 类型的 Key 中，并且可以设置逻辑过期时间，用于处理缓存击穿
     * @param: [key, value, time, unit] 
     * @return: void
     */

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }
/**
 * @description:  根据指定的 Key 查询缓存，反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题。
 * @param: [keyPrefix, id, type, dbFallback, time, unit] 
 * @return: R
 */
public <R, ID> R dealWithCacheHotspotInvalid(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit timeUnit) {

        // 1. 从redis中查询商铺缓存
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断是否存在
        if (StrUtil.isNotBlank(json)) {// 3. 存在，直接返回
            return JSONUtil.toBean(json, type);
        }
        // 4. 判断是否为空值
        if (json != null) {
            return null;
        }
        // 5. 不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        // 6. 不存在，返回错误
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "", time, timeUnit);
            return null;
        }
        // 7. 存在，存入redis
        this.setWithPassThrough(key, r, time, timeUnit);
        return r;
    }
/**
 * @description: 根据指定的 Key 查询缓存，反序列化为指定类型，利用逻辑过期的方式解决缓存击穿问题。
 * @param: [keyPrefix, id, type, dbFallback, time, unit] 
 * @return: R
 */
public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        // 1. 从redis中查询商铺缓存
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断是否存在
        if (StrUtil.isBlank(json)) {
            // 3. 不存在，直接返回
            return null;
        }
        // 4. 命中，把json反序列化成对象
        RedisData redisData;
        redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject jsonObject = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(jsonObject, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5. 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 5.1 未过期，直接返回店铺信息
            return r;
        }
        // 5.2 已过期，需要缓存重建

        // 6. 进行缓存重建
        // 6.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        // 6.2 判断是否获取锁成功
        boolean isLock = tryLock(lockKey);
        if (isLock) {
            // 再次检测

            json = stringRedisTemplate.opsForValue().get(key);
            redisData = JSONUtil.toBean(json, RedisData.class);
            jsonObject = (JSONObject) redisData.getData();
            r = JSONUtil.toBean(jsonObject, type);
            expireTime = redisData.getExpireTime();
            //  判断是否过期
            if (expireTime.isAfter(LocalDateTime.now())) {
                //  未过期，直接返回店铺信息
                return r;
            }

            // 6.3 成功开启新线程执行缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                // 重建缓存,查询数据库
                try {
                    R r1 = dbFallback.apply(id);
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放所
                    unLock(lockKey);
                }
            });
        }

        return r;
    }
/**
 * @description: 获取互斥锁
 * @param: [key]
 * @return: boolean
 */
private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10L, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    /**
     * @description: 释放互斥锁
     * @param: [key]
     * @return: void
     */

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

}
