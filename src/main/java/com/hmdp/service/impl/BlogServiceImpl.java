package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.ScrollResult;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private UserServiceImpl userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private BlogServiceImpl blogService;
    @Resource
    private FollowServiceImpl followService;

    @Override
    public Result queryById(Long id) {
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }
        queryBlogWithUserInfo(blog);
        //查询Blog是否被点赞
        isBlogLike(blog);
        return Result.ok(blog);
    }

    private void isBlogLike(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return;
        }
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = BLOG_LIKED_KEY + blog.getId();
        //判断是否已经点赞
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    @Override
    public Result likeBlog(Long id) {
        //判断当前用户是否点赞
        Long userId = UserHolder.getUser().getId();
        String key = BLOG_LIKED_KEY + id;

        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        if (BooleanUtil.isFalse(isMember)) {
            //如果未点赞，可以点赞，数据库+1
            boolean isSaveSuccess = update().setSql("liked = liked+1").eq("id", id).update();
            //保存用户到set集合
            if (isSaveSuccess) {
                stringRedisTemplate.opsForSet().add(key, userId.toString());
            }

        } else {
            //点赞取消
            boolean isSaveSuccess = update().setSql("liked = liked-1").eq("id", id).update();
            if (isSaveSuccess) {
                stringRedisTemplate.opsForSet().remove(key, userId.toString());
            }

        }
        return Result.ok();
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = blogService.query().orderByDesc("liked").page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            queryBlogWithUserInfo(blog);
            isBlogLike(blog);
        });

        return Result.ok(records);
    }

    @Override
    public Result queryBlogLikes(Long id) {
        //查询top5 的点赞用户 zrange key 0 4
        String key = BLOG_LIKED_KEY + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.fail("无人点赞");
        }

        //解析用户id
        List<Long> topFiveUserIds = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idsStr = StrUtil.join(",", topFiveUserIds);
        //根据id查询用户
        List<UserDTO> userDTOList = userService.query().in("id", topFiveUserIds).last("ORDER BY FIELD(id," + idsStr + ")").list().stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());

        //返回
        return Result.ok(userDTOList);
    }

    @Override
    public Result queryBlogByUserId(Integer current, Long id) {
        // 根据用户查询
        Page<Blog> page = blogService.query().eq("user_id", id).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSucceed = blogService.save(blog);
        if (BooleanUtil.isFalse(isSucceed)) {
            return Result.fail("发布失败～");
        }
        //查询粉丝
        List<Follow> followList = followService.lambdaQuery().eq(Follow::getFollowUserId, user.getId()).list();
        if (followList.isEmpty() || followList == null) {
            return Result.ok(blog.getId());
        }
        //推送笔记给所有粉丝
        for (Follow follow : followList) {
            // 粉丝ID
            Long userId = follow.getUserId();
            // 推送
            String key = FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }

        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1. 获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2. 查询收件箱 ZREVRANGEBYSCORE key max min LIMIT offset count
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> tupleSet = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        if (tupleSet.isEmpty() || tupleSet == null) {
            return Result.ok();
        }
        ArrayList<Object> ids = new ArrayList<>(tupleSet.size());
        long minTime = 0;
        int offsetNumber = 1;
        for (ZSetOperations.TypedTuple<String> tuple : tupleSet) {
            String idStr = tuple.getValue();
            ids.add(idStr);
            long time = tuple.getScore().longValue();
            if (time == minTime) {
                offsetNumber++;
            } else {
                minTime = time;
                offsetNumber = 1;
            }

        }

        // 3. 解析数据：blogId、lastId、offset
        // 4. 根据 ID 查询 Blog
        String blogIdStr = StrUtil.join(", ", ids);
        List<Blog> blogList = lambdaQuery().in(Blog::getId, ids).last("ORDER BY FIELD(id, " + blogIdStr + ")").list();
        for (Blog blog : blogList) {
            // 完善 Blog 数据：查询并且设置与 Blog 有关的用户信息，以及 Blog 是否被该用户点赞
            queryBlogWithUserInfo(blog);
            isBlogLike(blog);
        }

        // 5. 封装并返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogList);
        scrollResult.setMinTime(minTime);
        scrollResult.setOffset(offsetNumber);
        return Result.ok(scrollResult);

    }

    private void queryBlogWithUserInfo(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setIcon(user.getIcon());
        blog.setName(user.getNickName());
    }

}
