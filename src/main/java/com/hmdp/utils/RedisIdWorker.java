package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * @description:
 * @author: lyl
 * @time: 2023/6/20 12:25
 */
@Component
public class RedisIdWorker {

    private static final short CONUT_BITS = 32;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    private static final long BEGIN_TIME = 1640995200L;
    /**
     *@description:生成唯一id
     *
     * @param prefix 业务名字
     *
     * @return long id
     */
    public long nextId(String prefix){
        //生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowTime = now.toEpochSecond(ZoneOffset.UTC);
        long time = nowTime - BEGIN_TIME;

        //生成序列号
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = stringRedisTemplate.opsForValue().increment("incre:" + prefix + ":" + date);
        return time<<CONUT_BITS | count;
    }
}
