package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

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
    private RedisTemplate redisTemplate;
    @Override
    public Result queryListByRedis() {
        //查询redis缓存
        List<Shop> list = (List<Shop>)redisTemplate.opsForList().rightPop("shop-type");
        if (list != null) {
            return Result.ok(list);
        }
        List<ShopType> typeList = query().orderByAsc("sort").list();
        if (typeList == null) {
            return Result.fail("商铺类型不存在");
        }
        redisTemplate.opsForList().rightPush("shop-type", typeList);
        return Result.ok(typeList);
    }
}
