package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public List<ShopType> queryType() {
        // 1. 从Redis查询缓存（使用统一的Redis常量）
        String typeListJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_TYPE_KEY);

        // 2. 缓存存在，直接反序列化返回
        if (StrUtil.isNotBlank(typeListJson)) {
            return JSONUtil.toList(typeListJson, ShopType.class);
        }

        // 3. 缓存不存在，查询数据库（使用this调用父类的query方法）
        List<ShopType> typeList = this.query().orderByAsc("sort").list();

        // 4. 数据库无数据，返回空列表（而非Result）
        if (typeList == null || typeList.isEmpty()) {
            return new ArrayList<>();// 返回空列表，符合方法返回值类型
        }

        // 5. 将查询结果写入Redis（使用RedisConstants常量，设置过期时间）
        stringRedisTemplate.opsForValue().set(
                RedisConstants.CACHE_SHOP_TYPE_KEY,
                JSONUtil.toJsonStr(typeList),
                RedisConstants.CACHE_SHOP_TYPE_TTL,
                TimeUnit.MINUTES
        );

        // 6. 返回数据库查询的真实数据（核心修复：不再返回空列表）
        return typeList;
    }
}
