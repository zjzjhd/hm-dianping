package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE;
import static com.hmdp.utils.SystemConstants.TTL_THIRTY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private IShopTypeService typeService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result usingListToQueryByCacheOrderByAscSort() {
        // 1. 从 Redis 中查询；
        List<String> shopTypeJsonList  = stringRedisTemplate.opsForList().range(CACHE_SHOP_TYPE, 0, -1);
        // 2. Redis 中存在则直接返回；
        if(!shopTypeJsonList.isEmpty()){
            ArrayList<ShopType> shopTypeList = new ArrayList<>();
            for (String str :shopTypeJsonList){
                ShopType shopType = JSONUtil.toBean(str, ShopType.class);
                shopTypeList.add(shopType);
            }
            return Result.ok(shopTypeList);
        }
        // 3.Redis 中不存在则从数据库中查询；数据库中不存在则报错。
        List<ShopType> shopTypeList = typeService.query().orderByAsc("sort").list();
        if (shopTypeList.isEmpty() && shopTypeList == null) {
            return Result.fail("该店铺类型不存在！") ;
        }
        for (ShopType shopType : shopTypeList) {
            String shopTypeJson = JSONUtil.toJsonStr(shopType);
            shopTypeJsonList.add(shopTypeJson);
        }

        // 4. 数据库中存在，将其保存到 Redis 中并返回。
        stringRedisTemplate.opsForList().rightPushAll(CACHE_SHOP_TYPE, shopTypeJsonList);
        return Result.ok(shopTypeList);
    }
    @Override
    public Result usingStringToQueryByCacheOrderByAscSort() {

        // 1. 从 Redis 中查询
        String shopTypeJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE);

        // 2. Redis 中存在则直接返回
        if (StrUtil.isNotBlank(shopTypeJson)) {
            List<ShopType> shopTypeList = JSONUtil.toList(JSONUtil.parseArray(shopTypeJson), ShopType.class);
            return Result.ok(shopTypeList);
        }

        // 3. Redis 中不存在则从数据库中查询；数据库中不存在则报错.
        List<ShopType> shopTypeList =  typeService.query().orderByAsc("sort").list();
        if (shopTypeList.isEmpty() && shopTypeList == null) {
            return Result.fail("店铺类型不存在！") ;
        }

        // 4. 数据库中存在，将其保存到 Redis 中并返回
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_TYPE, JSONUtil.toJsonStr(shopTypeList), TTL_THIRTY, TimeUnit.MINUTES);
        return Result.ok(shopTypeList);
    }
}
