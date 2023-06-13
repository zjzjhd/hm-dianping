package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result isFollowed(Long followUserId) {

        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        //计算关注数量
        Integer count = lambdaQuery().eq(Follow::getUserId, userId).eq(Follow::getFollowUserId, followUserId).count();
        return Result.ok(count > 0);

    }

    @Override
    public Result followOrNot(Long followUserId, Boolean isFollowed) {
        Long userId = UserHolder.getUser().getId();
        String key = "follow:" + userId;
        // 判断是关注还是取关
        if (BooleanUtil.isTrue(isFollowed)) {
            // 关注，增加
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSucceed = save(follow);
            // 添加到 Redis 中（当前用户ID 为 key，关注用户ID 为 value）
            if (Boolean.TRUE.equals(isSucceed)) {
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        } else {
            // 取关，删除
            boolean isSucceed = remove(new LambdaQueryWrapper<Follow>()
                    .eq(Follow::getUserId, userId)
                    .eq(Follow::getFollowUserId, followUserId));
            if (BooleanUtil.isTrue(isSucceed)) {
                // 从 Redis 中删除
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }

        }
        return Result.ok();

    }

    @Override
    public Result commonFollow(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
        String selfKey = "follow:" + userId;
        String aimKey = "follow:" + followUserId;
        Set<String> userIdSet = stringRedisTemplate.opsForSet().intersect(selfKey, aimKey);
        if (userIdSet.isEmpty() || userIdSet == null) {
            // 无交集
            return Result.ok(Collections.emptyList());
        }
        List<UserDTO> userDTOList = listByIds(userIdSet)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOList);

    }
}
