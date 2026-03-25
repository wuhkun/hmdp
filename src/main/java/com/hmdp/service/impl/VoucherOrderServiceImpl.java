package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
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
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;


    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;



    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        // 从 classpath 加载 seckill.lua 脚本文件
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        // 指定脚本返回值类型为 Long
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private static final ExecutorService EXECUTOR_SERVICE = Executors.newCachedThreadPool();
    @PostConstruct
    public void init() {
        IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
        EXECUTOR_SERVICE.submit(new VoucherOrderTask(proxy));
    }
    private static final String QUEUE_NAME = "stream.orders";
    private class VoucherOrderTask implements Runnable {

        private final IVoucherOrderService proxy;

        public VoucherOrderTask(IVoucherOrderService proxy) {
            this.proxy = proxy;
        }

        @Override
        public void run() {

            while (true) {
                try {
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"), // 消费者组 g1，消费者名称 c1
                            StreamReadOptions.empty()
                                    .count(1) // 每次读取 1 条消息
                                    .block(Duration.ofSeconds(2)), // 阻塞等待 2 秒，无消息则返回
                            StreamOffset.create(QUEUE_NAME, ReadOffset.lastConsumed()) // 从上次消费的位置继续读
                    );
                    if (list == null || list.isEmpty()) {

                        continue;
                    }
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = new VoucherOrder();
                    voucherOrder.setId(Long.valueOf((String) value.get("id")));
                    voucherOrder.setUserId(Long.valueOf((String) value.get("userId")));
                    voucherOrder.setVoucherId(Long.valueOf((String) value.get("voucherId")));
                    handlerVoucherOrder(voucherOrder, proxy);
                    stringRedisTemplate.opsForStream().acknowledge("stream.orders", "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList(proxy);
                }

            }
        }
    }

    private void handlePendingList(IVoucherOrderService proxy) {
        while (true) {
            try {
                // 读取 Pending 消息（从 0 开始读）
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(QUEUE_NAME, ReadOffset.from("0"))
                );

                // 无 Pending 消息，结束循环
                if (list == null || list.isEmpty()) {
                    break;
                }

                // 处理消息
                MapRecord<String, Object, Object> record = list.get(0);
                Map<Object, Object> value = record.getValue();
                VoucherOrder voucherOrder = new VoucherOrder();
                voucherOrder.setId(Long.valueOf((String) value.get("id")));
                voucherOrder.setUserId(Long.valueOf((String) value.get("userId")));
                voucherOrder.setVoucherId(Long.valueOf((String) value.get("voucherId")));
                handlerVoucherOrder(voucherOrder, proxy);
                stringRedisTemplate.opsForStream().acknowledge("stream.orders", "g1", record.getId());
            } catch (Exception e) {
                log.error("处理 Pending List 异常", e);
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void handlerVoucherOrder(VoucherOrder take, IVoucherOrderService proxy) {
        Long userId = take.getUserId();
        Long voucherId = take.getVoucherId();
        String lockKey = "lock:seckill:voucher:" + voucherId + ":user:" + userId;
        RLock lock = redissonClient.getLock(lockKey);

        boolean isLock = false;
        try {
            isLock = lock.tryLock(500, 30, TimeUnit.SECONDS);
            if (!isLock) {
                log.error("一人只能一单");
                return;
            }
            proxy.createVoucher(take);
        }catch (InterruptedException e) {
            // 捕获抢锁中断异常，记录日志
            log.error("用户{}获取优惠券{}秒杀锁失败", userId, voucherId, e);
            log.error("系统繁忙，请稍后再试！");
        } finally {
            if (isLock) {
                lock.unlock();
            }
        }
    }



    @Override
    public Result seckillVoucher(Long voucherId) {

        Long userId= UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        Long execute = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(),  voucherId.toString(), userId.toString(),String.valueOf(orderId));
        int r = execute.intValue();
        if (r != 0) {
            // 2.1. 不为0，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }


        return Result.ok(orderId);

    

    }

    @Transactional
    public void createVoucher(VoucherOrder voucherorder) {
        //一人一单
        Long userId = voucherorder.getUserId();

            Integer count = query().eq("user_id", userId).eq("voucher_id", voucherorder.getVoucherId()).count();
            if (count > 0) {
                log.error("已经买了");
                throw new RuntimeException("该用户已购买过此优惠券");
            }
            // 5. 扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1")
                    .eq("voucher_id", voucherorder.getVoucherId()).gt("stock",0)
                    .update();
            if (!success) {
                // 扣减失败
                log.error("库存不足");
                throw new RuntimeException("库存不足");
            }
            // 6.1. 订单id
            save(voucherorder);

    }
}
