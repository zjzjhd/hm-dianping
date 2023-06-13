package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * @description: 发送验证码
     * @param: [phone, session]
     * @return: Result
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1. 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2. 如果不符合，返回错误信息
            return Result.fail("手机号格式错误");
        }
        //3. 符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

        //4. 保存验证码到session
        //session.setAttribute("code", code);
        //保存到redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //5. 发送验证码
        log.info(code);
        return Result.ok();
    }

    /**
     * @description: 短信验证码注册和登录
     * @param: [loginForm, session]
     * @return: Result
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2. 如果不符合，返回错误信息
            return Result.fail("手机号格式错误");
        }
        // 从redis获取验证码并校验
        String code = loginForm.getCode();
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        //Object cacheCode = session.getAttribute("code");
        if (cacheCode == null || !cacheCode.toString().equals(code)) return Result.fail("请重新输入验证码");
        // 如果一致
        // 根据手机查询用户

        User user = query().eq("phone", phone).one();
        // 判断用户是否存在
        if (user == null) {
            //如果不存在则保存
            user = createUserByPhone(phone);

        }
        //保存用户信息到redis
        // 7.1.随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString();
        // 7.2.将User对象转为HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(), CopyOptions.create().setIgnoreNullValue(true).setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        //session.setAttribute("user", user);
        //7.保存用户信息到session中
        //session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        // 7.3.存储
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        // 7.4.设置token有效期
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);

        // 8.返回token
        return Result.ok(token);

    }

    @Override
    /**
     * @description: 获取当前用户
     * @param:
     * @return: Result
     */

    public Result getCurrentUser() {
        UserDTO user = UserHolder.getUser();
        return Result.ok(user);
    }

    /**
     * @description: 根据id查询用户
     * @param: [userId]
     * @return: Result
     */
    @Override
    public Result queryUserById(Long userId) {
        // 查询详情
        User user = getById(userId);
        if (user == null) {
            return Result.ok();
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 返回
        return Result.ok(userDTO);
    }

    @Override
    public Result sign() {
        // 1. 获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2. 获取日期
        LocalDateTime nowTime = LocalDateTime.now();
        String formatTime = nowTime.format(DateTimeFormatter.ofPattern(":yyyyMM"));

        // 3. 拼接 Key
        String key = USER_SIGN_KEY + userId + formatTime;
        // 4. 获取今天是本月的第几天（1～31，BitMap 则为 0～30）
        int dayOfMonth = nowTime.getDayOfMonth();

        // 5. 写入 Redis  SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();

    }

    @Override
    public Result signCount() {
        // 1. 获取当前登录用户
        Long userId = UserHolder.getUser().getId();

        // 2. 获取日期
        LocalDateTime nowDateTime = LocalDateTime.now();
        String formatTime = nowDateTime.format(DateTimeFormatter.ofPattern(":yyyyMM"));

        // 3. 拼接 Key
        String key = USER_SIGN_KEY + userId + formatTime;
        // 4. 获取今天是本月的第几天（1～31，BitMap 则为 0～30）
        int dayOfMonth = nowDateTime.getDayOfMonth();
        // 5. 获取本月截止今天的所有签到记录，返回的是一个 十进制数字
        int bitCount = Integer.bitCount(dayOfMonth);
        System.out.println(bitCount);
        BitFieldSubCommands bitFieldSubCommands = BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0);

        List<Long> result = stringRedisTemplate.opsForValue().bitField(key,bitFieldSubCommands);
        System.out.println(result.get(0));
        if (result.isEmpty() || result == null) {
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == 0 || num == null) {
            return Result.ok(0);
        }
        // 6. 让这个数字与 1 做 与运算，得到数字的最后一个 Bit 位；判断这个 Bit 位是否为 0。
        int count = 0;
        while (true) {
            // 0：未签到，结束
            if ((num & 1) == 0) {
                break;
            } else {
                // 非0：签到，计数器 +1
                count++;
            }
            // 右移一位，抛弃最后一个 Bit 位，继续下一个 Bit 位。
            // num = num >> 1;
            num >>>= 1;
        }

        return Result.ok(count);
    }


    /**
     * @description: 根据手机创建用户
     * @param: [phone]
     * @return: User
     */
    private User createUserByPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 保存用户
        save(user);
        return user;

    }
}

