package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClientUtils;
import com.hmdp.utils.RedisData;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.DEFAULT_PAGE_SIZE;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    @Resource
    public IShopService shopService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClientUtils cacheClientUtils;

    @Override
    public Result queryById(Long id) {
        //缓存穿透
        Shop shop = cacheClientUtils.dealWithCacheHotspotInvalid(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);
        //逻辑过期解决缓存击穿
        //Shop shop = queryWithLogicalExpire(id);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    /**
     * @description: 对热点数据进行预热
     * @param: [id, expireSeconds]
     * @return: void
     */
    public void saveShopToRedis(long id, Long expireSeconds) {
        //查询店铺数据
        Shop shop = getById(id);

        //封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_TYPE + id, JSONUtil.toJsonStr(redisData));
    }

    /**
     * @description: 使用互斥锁查询
     * @param: [id]
     * @return: Shop
     */
    private Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1. 从 Redis 中查询店铺缓存；
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 若 Redis 中存在，则将其转换为 Java 对象后返回；
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //判断命中的是否为空值
        if (shopJson != null) {
            //返回一个错误信息
            return null;
        }

        //未命中获取互斥锁
        String lockKey = "Lock:shop:" + id;
        Shop shop = null;
        try {
            boolean tryLockFlag = tryLock(lockKey);
            //判断是否成功
            if (!tryLockFlag) {
                //失败休眠，并重试
                Thread.sleep(50);
                //使用循环重试
                queryWithMutex(id);

            }
            //成功
            // 3. 若 Redis 中不存在，则根据 id 从数据库中查询；
            shop = shopService.getById(id);

            // 4. 若 数据库 中不存在，则报错；
            if (shop == null) {
                //将空值写入redis
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 5. 若 数据库 中存在，则将其返回并存入 Redis 缓存中。
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 释放锁
            unlock(lockKey);
        }

        return shop;
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        String key = CACHE_SHOP_KEY + id;
        //1. 更新数据库
        updateById(shop);
        //  2. 删除缓存
        stringRedisTemplate.delete(key);
        return Result.ok();
    }

    @Override
    public Result queryShopByTypeId(Integer typeId, Integer current, Double x, Double y) {
        // 1. 判断是否需要根据坐标查询
        if (ObjectUtil.isNull(x) || ObjectUtil.isNull(y)) {
            return Result.ok(lambdaQuery().eq(Shop::getTypeId, typeId).page(new Page<>(current, DEFAULT_PAGE_SIZE)).getRecords());
        }

        // 2. 计算分页参数
        int start = (current - 1) * DEFAULT_PAGE_SIZE;
        int end = current * DEFAULT_PAGE_SIZE;

        // 3. 查询 Redis，按照距离排序 --> GEOSEARCH key BYLONLAT x y BYRADIUS 5000 mi WITHDISTANCE
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> geoResults = stringRedisTemplate.opsForGeo().search(key, GeoReference.fromCoordinate(x, y), new Distance(5000), RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end));
        if (ObjectUtil.isNull(geoResults)) {
            return Result.ok(Collections.emptyList());
        }

        // 4. 解析出 ID，根据 ID 查询商店
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = geoResults.getContent();
        if (content.size() <= start) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> shopIdList = new ArrayList<>(content.size());
        Map<String, Distance> distanceMap = new HashMap<>(content.size());
        content.stream().skip(start).forEach(result -> {
            String shopIdStr = result.getContent().getName();
            shopIdList.add(Long.valueOf(shopIdStr));
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });

        // 5. 根据 shopId 查询 Shop
        String shopIdStrWithComma = StrUtil.join(", ", shopIdList);
        List<Shop> shopList = lambdaQuery().in(Shop::getId, shopIdList).last("ORDER BY FIELD(id, " + shopIdStrWithComma + ")").list();
        for (Shop shop : shopList) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }

        // 6. Return ShopList
        return Result.ok(shopList);

    }

    /**
     * @description: 获取互斥锁
     * @param: [key]
     * @return: boolean
     */
    private boolean tryLock(String key) {
        //获取互斥锁
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);

    }

    /**
     * @description: 删除互斥锁
     * @param: [key]
     * @return: void
     */
    private void unlock(String key) {
        //删除互斥锁
        stringRedisTemplate.delete(key);
    }

    /**
     * @description: 使用逻辑过期解决
     * @param: [id]
     * @return: Shop
     */
    public Shop queryWithLogicalExpire(long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1. 从 Redis 中查询店铺缓存；
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 若 Redis中不存在，直接返回；
        if (StrUtil.isBlank(shopJson)) {
            return null;
        }
        // 3. 命中，需要把接送反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //未过期，直接返回店铺信息
            return shop;
        }
        //已过期，需要缓存重建
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 5.1 获取到互斥锁
        // 开启独立线程：根据 id 查询数据库，将数据写入到 Redis，并且设置逻辑过期时间。
        // 此处必须进行 DoubleCheck
        // 多线程并发下，若线程1 和 线程2都到达这一步，线程1 拿到锁，进行操作后释放锁；线程2 拿到锁后会再次进行查询数据库、写入到 Redis 中等操作。
        shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(shopJson)) {
            return null;
        }
        if (isLock) {
            // 再次检测

            shopJson = stringRedisTemplate.opsForValue().get(key);
            redisData = JSONUtil.toBean(shopJson, RedisData.class);
            shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
            expireTime = redisData.getExpireTime();

            //开启独立线程
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 重建缓存
                    this.saveShopToRedis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
        }

        return shop;
    }

    public Shop queryWithPassThrough(long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1. 从 Redis 中查询店铺缓存；
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 若 Redis 中存在，则将其转换为 Java 对象后返回；
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //判断命中的是否为空值
        if (shopJson != null) {
            return null;
        }
        // 3. 若 Redis 中不存在，则根据 id 从数据库中查询；
        Shop shop = shopService.getById(id);

        // 4. 若 数据库 中不存在，则报错；
        if (shop == null) {
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 5. 若 数据库 中存在，则将其返回并存入 Redis 缓存中。
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

}
