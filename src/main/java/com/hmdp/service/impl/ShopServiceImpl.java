package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.*;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

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
    @Resource
    private CacheClient cacheClient;
    @Override
    public Result queryIdByRedis(Long id) {
        //缓存穿透
//        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY,id,Shop.class,id1->getById(id1),CACHE_SHOP_TTL,TimeUnit.MINUTES);
        //互斥锁解决缓存击穿
//        Shop shop1 = queryWithMutex(id);
        //逻辑过期解决缓存击穿
        Shop shop = cacheClient.queryWithLogicExpire(CACHE_SHOP_KEY,id,Shop.class,id1->getById(id1),CACHE_SHOP_TTL,TimeUnit.MINUTES);

        if (shop == null) {
            return Result.fail("shop is null");
        }
        return Result.ok(shop);
    }
    private Shop queryWithMutex(Long id){
        //1.redis查询是否有缓存
        String Key = CACHE_SHOP_KEY + id;
        String JsonShop = stringRedisTemplate.opsForValue().get(Key);
        //2.存在返回

        if(StrUtil.isNotBlank(JsonShop)){
            return JSONUtil.toBean(JsonShop, Shop.class);
        }
        //3.店铺不存在
        if (JsonShop != null) {
            return null;
        }
        //4.重建缓存
        String mutexKey = RedisConstants.LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            //4.1获取锁
            boolean getLock = tryLock(mutexKey);
            if (!getLock ) {
                //4.2没得到锁就休息在查询
                Thread.sleep(50);
                queryWithMutex(id);
            }
            //4.3获取锁再检查缓存有无被重建
            String JsonShop1 = stringRedisTemplate.opsForValue().get(Key);
            if(StrUtil.isNotBlank(JsonShop1)){
                return JSONUtil.toBean(JsonShop1, Shop.class);
            }

            if (JsonShop1 != null) {
                return null;
            }
            //4.4缓存没重建，查数据库
            shop = getById(id);
            //5.shop不存在返回
            if (shop == null) {
                //将""写入redis，防止缓存穿透
                stringRedisTemplate.opsForValue().set(Key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }
            //6.存在写入redis
            stringRedisTemplate.opsForValue().set(Key,JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //7.重建完成释放锁
            stringRedisTemplate.delete(mutexKey);
        }
        return shop;
    }
    /*private static final ExecutorService CACHE_REBUILD_EXECUTOR = new ThreadPoolExecutor(
            1, 2, 1000, TimeUnit.MILLISECONDS,
            new SynchronousQueue<Runnable>(),Executors.defaultThreadFactory(),new ThreadPoolExecutor.AbortPolicy());
    private Shop queryWithLogicExpire(Long id){
        //1.redis查询是否有缓存
        String Key = CACHE_SHOP_KEY + id;
        String JsonShop = stringRedisTemplate.opsForValue().get(Key);
        //2.存在返回

        if(StrUtil.isBlank(JsonShop)){
            return null;
        }

       //3.命中，判断缓存是否过期
        RedisData redisData = JSONUtil.toBean(JsonShop, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        if(expireTime.isAfter(LocalDateTime.now())){
            //3.1未过期，返回商店
            return shop;
        }

        //3.2过期，重建缓存
        String mutexKey = RedisConstants.LOCK_SHOP_KEY + id;

        //4.获取互斥锁
        boolean b = tryLock(mutexKey);
        if(b){
            //4.1成功，开启线程重建缓存
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    saveShop2Redis(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    stringRedisTemplate.delete(mutexKey);
                }
            });
        }
        //4.2成功失败返回旧的商店信息，下次查询返回新的商店
        return shop;
    }*/
    private boolean tryLock(String key){
        Boolean f = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 2, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(f);
    }

    /*private Shop queryWithPassThrough(Long id){
        //1.redis查询是否有缓存
        String Key = RedisConstants.CACHE_SHOP_KEY + id;
        String JsonShop = stringRedisTemplate.opsForValue().get(Key);
        //2.存在返回

        if(StrUtil.isNotBlank(JsonShop)){
            Shop shop = JSONUtil.toBean(JsonShop, Shop.class);
            return shop;
        }
        if (JsonShop != null) {
            return null;
        }
        //3.不存在，查询数据库
        Shop shop = getById(id);
        //4.shop不存在返回
        if (shop == null) {
            //将""写入redis
            stringRedisTemplate.opsForValue().set(Key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        //5.存在写入redis
        stringRedisTemplate.opsForValue().set(Key,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //6.返回
        return shop;
    }*/
   /* public void saveShop2Redis(Long id , Long expireTime){
        Shop shop = getById(id);
        RedisData redisData = new RedisData(LocalDateTime.now().plusSeconds(expireTime),shop);
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));

    }*/

    @Override
    @Transactional
    public Result updateIdByRedis(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("id is null");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+ id);
        return Result.ok();
    }
}
