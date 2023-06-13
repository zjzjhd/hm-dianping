package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import com.hmdp.service.impl.FollowServiceImpl;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {
    @Resource
    private IFollowService followService;

    /**
     * @description:判断是否关注该用户
     * @param: [followUserId]需要关注 or 取关的 用户ID
     * @return: Result
     */
    @GetMapping("/or/not/{id}")
    public Result isFollowed(@PathVariable("id") Long followUserId) {
        return followService.isFollowed(followUserId);
    }

    /**
     * @description: 关注或取关
     * @param: followUserId 关注用户的ID
     * @return: null
     */

    @PutMapping("/{id}/{isFllow}")
    public Result followOrNot(@PathVariable("id") Long followUserId, @PathVariable("isFollowed") Boolean isFollowed) {
        return followService.followOrNot(followUserId, isFollowed);
    }
    @GetMapping("/common/{id}")
    public Result commonFollow(@PathVariable("id") Long followUserId){
        return followService.commonFollow(followUserId);
    }


}


