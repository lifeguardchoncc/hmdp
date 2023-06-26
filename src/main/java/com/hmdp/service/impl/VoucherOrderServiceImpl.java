package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.RedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
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
    @Override
    /**
     *@description:声明事务，方法内操作要么都发生要么都不发生，可以出现错误进行回滚。不保证对数据库操作互斥
     * ，假设先使用 select ，然后 update ，即使目标方法使用了 synchronized，但因为提交事务在@Transactional
     * 注解方法之外，即使方法执行完成了，但事务还未提交，意味着update 没有生效，若再来一个线程执行 select 就读到了脏数据。
     *
     * @param voucherId
     *
     * @return com.hmdp.dto.Result
     */
    @Transactional
    public Result seckillVoucher(Long voucherId) {

        //1.查询优惠券
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        //2.判断秒杀开始结束
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("未开始");
        }
        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("已结束");
        }
        //3.判断库存
        if(seckillVoucher.getStock()<1){
            return Result.fail("库存不足");
        }
        //一人一单
        Long userId = UserHolder.getUser().getId();
        //RedisLock redisLock = new RedisLock("order:" + userId, stringRedisTemplate);
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = redisLock.tryLock();
        if(!isLock){
            return Result.fail("不允许重复下单");
        }
        try {
            IVoucherOrderService proxy  = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createSeckillVoucher(userId,voucherId);
        } finally {
            redisLock.unlock();
        }
        //防止事务未提交就有线程去查询用户的订单量，所以锁住整个方法
  /*     synchronized (userId.toString().intern()){
           //用代理对象去处理事务，否则会事务失效
           IVoucherOrderService proxy  = (IVoucherOrderService) AopContext.currentProxy();
           return proxy.createSeckillVoucher(userId,voucherId);
       }*/
    }
    @Transactional
    public Result createSeckillVoucher(Long userId, Long voucherId) {
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if(count>0){
            return Result.fail("用户已买过一次");
        }
        //4.扣减库存，InnoDB引擎会自动给UPDATE、INSERT、DELETE语句添加排他锁，所以通过这样的语句可以防止超卖。
        boolean suc = seckillVoucherService.update().setSql("stock = stock-1")
                .eq("voucher_id", voucherId)
                //看库存是否大于0解决超卖问题
                .gt("stock",0).update();
        if(!suc){
            return Result.fail("库存不足");
        }

        //5.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(userId);
        Long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        save(voucherOrder);
        //6.返回
        return Result.ok(orderId);
    }
}
