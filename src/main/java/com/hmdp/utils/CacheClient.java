package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * @description:
 * @author: lyl
 * @time: 2023/6/20 10:45
 */
@Slf4j
@Component
public class CacheClient {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }
    public void setWithLogicExpire(String key, Object value, Long time, TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }
    public <R,ID>R queryWithPassThrough(String prefix,ID id, Class<R>type, Function<ID,R> dbFallBack, Long time, TimeUnit unit){
        //1.redis查询是否有缓存
        String Key = prefix + id;
        String json = stringRedisTemplate.opsForValue().get(Key);
        //2.存在返回

        if(StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            return null;
        }
        //3.不存在，查询数据库
        R res = dbFallBack.apply(id);
        //4.shop不存在返回
        if (res == null) {
            //将""写入redis
            set(Key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        //5.存在写入redis
        set(Key,res,time,unit);
        //6.返回
        return res;
    }
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = new ThreadPoolExecutor(
            1, 2, 1000, TimeUnit.MILLISECONDS,
            new SynchronousQueue<Runnable>(), Executors.defaultThreadFactory(),new ThreadPoolExecutor.AbortPolicy());
    public  <R,ID>R queryWithLogicExpire(String prefix,ID id, Class<R>type, Function<ID,R> dbFallBack, Long time, TimeUnit unit){
        //1.redis查询是否有缓存
        String Key = prefix + id;
        String json = stringRedisTemplate.opsForValue().get(Key);
        //2.存在返回

        if(StrUtil.isBlank(json)){
            return null;
        }

        //3.命中，判断缓存是否过期
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        if(expireTime.isAfter(LocalDateTime.now())){
            //3.1未过期，返回商店
            return r;
        }

        //3.2过期，重建缓存
        String mutexKey = LOCK_SHOP_KEY + id;

        //4.获取互斥锁
        boolean b = tryLock(mutexKey);
        if(b){
            //4.1成功，开启线程重建缓存
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    R r1 = dbFallBack.apply(id);
                    setWithLogicExpire(Key,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    stringRedisTemplate.delete(mutexKey);
                }
            });
        }
        //4.2成功失败返回旧的商店信息，下次查询返回新的商店
        return r;
    }
    private boolean tryLock(String key){
        Boolean f = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 2, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(f);
    }

}
