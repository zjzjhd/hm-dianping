package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Transactional
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    // Lua 脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    // 异步处理线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    static {
        SECKILL_SCRIPT = new DefaultRedisScript();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("Unlock.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private SeckillVoucherServiceImpl seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    // 代理对象
    private IVoucherOrderService currentProxy;
    // 阻塞队列：当一个线程尝试从队列中获取元素时：若队列中没有元素线程就会被阻塞，直到队列中有元素时线程才会被唤醒并且去获取元素。
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    // 在当前类初始完毕后执行 VoucherOrderHandler 中的 run 方法
    @PostConstruct
    public void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLocked = lock.tryLock();
        if (!isLocked) {
            log.error("不允许重复下单！");
            return;
        }
        try {
            // 该方法非主线程调用，代理对象需要在主线程中获取。
            currentProxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        //1. 执行Lua脚本 判断资格和库存
        Long executeResult = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), userId.toString(), String.valueOf(orderId)

        );

        //判断结果是否为0
        int result = executeResult.intValue();
        // 不为0没有购买资格
        if (result != 0) {
            return Result.fail(result == 1 ? "库存不足" : "不能重复下单");
        }

//        //6.创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        // 6.1.订单id
//        long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//        // 6.2.用户id
//
//        voucherOrder.setUserId(userId);
//        // 6.3.代金券id
//        voucherOrder.setVoucherId(voucherId);
//        //放入阻塞队列
//        orderTasks.add(voucherOrder);

        //返回订单id
        // 4. 获取代理对象
        currentProxy = (IVoucherOrderService) AopContext.currentProxy();

        // 5. 返回订单号（告诉用户下单成功，业务结束；执行异步下单操作数据库）
        return Result.ok(orderId);

    }

    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 一人一单（根据 优惠券id 和 用户id 查询订单；存在，则直接返回）
        Long userId = UserHolder.getUser().getId();
        //
        Long voucherId = voucherOrder.getVoucherId();

        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            log.error("不可重复下单！");
            return;
        }

        //5，扣减库存
        boolean success = seckillVoucherService.update().setSql("stock= stock -1").eq("voucher_id", voucherId).gt("stock", 0).update(); //where id = ? and stock > 0
        if (!success) {
            //扣减库存
            log.error("库存不足！");
            return;
        }

        boolean isSaved = save(voucherOrder);
        if (!isSaved) {
            log.error("失败");
            return;
        }

    }

    private void handlePendingList() {
        String queueName = "stream.orders";
        String groupName = "orderGroup";
        String consumerName = "consumerOne";

        while (true) {
            try {
                // 1. 获取队列中的订单信息
                // XREAD GROUP orderGroup consumerOne COUNT 1 BLOCK 2000 STREAMS stream.orders >
                // 队列 stream.orders、消费者组 orderGroup、消费者 consumerOne、每次读 1 条消息、阻塞时间 2s、从下一个未消费的消息开始。
                List<MapRecord<String, Object, Object>> readingList = stringRedisTemplate.opsForStream().read(
                        Consumer.from(groupName, consumerName),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(queueName, ReadOffset.from("0"))
                );

                //2. 判断消息是否获取成功
                if (readingList.isEmpty() || readingList == null) {
                    // 获取失败 pending-list 中没有异常消息，结束循环
                    break;
                }
                //3. 如果成功，可以下单
                // 3. 解析消息中的订单信息并下单
                MapRecord<String, Object, Object> record = readingList.get(0);
                Map<Object, Object> recordValue = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(recordValue, new VoucherOrder(), true);
                handleVoucherOrder(voucherOrder);

                //4. ACK确认
                stringRedisTemplate.opsForStream().acknowledge(queueName, groupName, record.getId());

            } catch (Exception e) {
                log.error("订单处理异常（pending-list）", e);

                try {
                    // 稍微休眠一下再进行循环
                    Thread.sleep(20);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        }
    }

    public class VoucherOrderHandler implements Runnable {
        String queueName = "stream.orders";
        String groupName = "orderGroup";
        String consumerName = "consumerOne";

        @Override
        public void run() {
            while (true) {
                try {
                    // 1. 获取队列中的订单信息
                    // XREAD GROUP orderGroup consumerOne COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    // 队列 stream.orders、消费者组 orderGroup、消费者 consumerOne、每次读 1 条消息、阻塞时间 2s、从下一个未消费的消息开始。
                    List<MapRecord<String, Object, Object>> readingList =
                            stringRedisTemplate.opsForStream()
                                    .read(
                                            Consumer.from(groupName, consumerName),
                                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                                            StreamOffset.create(queueName, ReadOffset.lastConsumed()));
                    //2. 判断消息是否获取成功
                    if (readingList.isEmpty() || readingList == null) {
                        // 获取失败 pending-list 中没有异常消息，结束循环
                        continue;
                    }
                    //3. 如果成功，可以下单
                    // 3. 解析消息中的订单信息并下单
                    MapRecord<String, Object, Object> record = readingList.get(0);
                    Map<Object, Object> recordValue = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(recordValue, new VoucherOrder(), true);
                    //3. 如果成功，可以下单
                    handleVoucherOrder(voucherOrder);

                    //4. ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, groupName, record.getId());

                } catch (Exception e) {
                    log.error("订单处理异常（pending-list）", e);
                    handlePendingList();
                }
            }
        }
    }

}

//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 1.查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        // 2.判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            // 尚未开始
//            return Result.fail("尚未开始");
//        }
//
//        // 3.判断秒杀是否已经结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("已经结束");
//        }
//        // 4.判断库存是否充足
//        if (voucher.getStock() < 1) {
//            // 库存不足
//            return Result.fail("库存不足！");
//        }
//
//        //先获取锁才能确保线程安全
//        return creatVoucherOrder(voucherId);
//
//    }

// 从队列中获取信息



