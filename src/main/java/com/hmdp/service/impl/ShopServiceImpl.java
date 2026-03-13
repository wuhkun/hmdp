package com.hmdp.service.impl;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public Shop queryWithMutex(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        // 1. 从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3. 存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 判断命中的是否是空值（缓存穿透防护）
        if (shopJson != null) {
            return null;
        }

        // 4. 缓存重建（互斥锁 + 循环重试）
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        // 核心优化：循环重试 + 最大重试次数限制
        int maxRetryTimes = 5; // 最多重试5次
        int retryCount = 0;
        boolean isLock = false;

        while (retryCount < maxRetryTimes) {
            // 生成当前线程唯一的锁标识（每次重试都生成新的，避免重复）
            String lockValue = UUID.randomUUID().toString();
            try {
                // 4.1 尝试获取锁（传入唯一value）
                isLock = tryLock(lockKey, lockValue);
                if (isLock) {
                    // 4.2 获取锁成功，查数据库重建缓存
                    shop = getById(id);
                    // 4.3 数据库不存在，缓存空值（防穿透）
                    if (shop == null) {
                        stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                        return null;
                    }
                    // 4.4 数据库存在，写入缓存
                    stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
                    break; // 重建成功，退出循环
                } else {
                    // 4.5 获取锁失败，休眠后重试
                    retryCount++;
                    Thread.sleep(50); // 每次休眠50ms
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                // 4.6 释放锁（传入唯一value，校验归属后释放）
                if (isLock) {
                    unlock(lockKey, lockValue);
                }
            }
        }

        // 5. 重试次数耗尽仍未获取锁，返回数据库查询结果（兜底）
        if (shop == null) {
            shop = getById(id); // 最后兜底查一次数据库
            // 兜底查询也为空，缓存空值
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            }
        }
        return shop;
    }

    @Override
    public Result queryById(Long id) {
        Shop shop = queryWithMutex(id);
        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);
    }

    private String getLockValue() {
        return UUID.randomUUID().toString();
    }

    private boolean tryLock(String key, String value) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, value, 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key, String value) {
        // 先获取当前锁的值
        String currentValue = stringRedisTemplate.opsForValue().get(key);
        // 校验锁的归属，只有匹配时才删除
        if (value.equals(currentValue)) {
            stringRedisTemplate.delete(key);
        }
    }



    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result updateshop(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不为空");
        }
        updateById(shop);
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY+id);
        return Result.ok();
    }
}
