package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * @description:
 * @author: lyl
 * @time: 2023/6/25 12:01
 */

public class RedisLock implements ILock {
    private String serviceName;
    private StringRedisTemplate stringRedisTemplate;

    public RedisLock(String serviceName, StringRedisTemplate stringRedisTemplate) {
        this.serviceName = serviceName;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String KEY_PREFIX = "lock:";
    private static final String THEAD_PREFIX = UUID.randomUUID().toString(true);
    private static final DefaultRedisScript<Long> UNLOCK_REDIS_SCRIPT;
    static {
        UNLOCK_REDIS_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_REDIS_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_REDIS_SCRIPT.setResultType(Long.class);
    }
    @Override
    public boolean trylock(long timeoutsec) {
        long threadId = Thread.currentThread().getId();
        Boolean suc = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + serviceName,
                THEAD_PREFIX+threadId , timeoutsec, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(suc);
    }

    @Override
    public void unlock() {
        //调用lua脚本，把判断线程标识和释放锁作为原子操作
         stringRedisTemplate.execute(UNLOCK_REDIS_SCRIPT,
                Collections.singletonList(KEY_PREFIX + serviceName),
                THEAD_PREFIX + Thread.currentThread().getId());
    }

    /*@Override
    public void unlock() {
        String threadId1 = THEAD_PREFIX + Thread.currentThread().getId();
        String threadId2 = stringRedisTemplate.opsForValue().get(KEY_PREFIX + serviceName);
        if(threadId1.equals(threadId2))
        stringRedisTemplate.delete(KEY_PREFIX + serviceName);
    }*/
}
