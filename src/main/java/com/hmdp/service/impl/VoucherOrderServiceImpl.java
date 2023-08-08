package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
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
import java.time.LocalDateTime;
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
    private static final DefaultRedisScript<Long> SECKILL_REDIS_SCRIPT;
    static {
        SECKILL_REDIS_SCRIPT = new DefaultRedisScript<>();
        SECKILL_REDIS_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_REDIS_SCRIPT.setResultType(Long.class);
    }

    private static final ExecutorService SECKILL_ORDER_SERVICE = Executors.newSingleThreadExecutor();
    //构造函数完成后立即执行
    @PostConstruct
    private void init(){
        SECKILL_ORDER_SERVICE.submit(new VoucherOrderHandeler());
    }
    private class VoucherOrderHandeler implements Runnable{
        String queueName = "stream.orders";

        @Override
        public void run() {
            while (true){
                try {
                    //1.消息队列拿到订单，取不到订单就阻塞2s
                    List<MapRecord<String, Object, Object>> mq = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );

                    //2.判断消息是否获取成功
                    if(mq == null || mq.isEmpty()){
                        //2.1如果失败，没有消息就下一次循环
                        continue;
                    }
                    MapRecord<String, Object, Object> entries = mq.get(0);
                    //拿到键值对,'voucherId',voucherId,'userId',userId,'id',orderId
                    Map<Object, Object> value = entries.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);

                    //2.2成功就下单
                    handleVoucherOrder(voucherOrder);

                    //3.ack确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",entries.getId());
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                    handlePendinglist();
                }
            }
        }
        private void handlePendinglist() {
            while (true){
                try {
                    //1.pendinglist拿到未确认的订单，0表示从pendinglist拿
                    List<MapRecord<String, Object, Object>> mq = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );

                    //2.判断消息是否获取成功
                    if(mq == null || mq.isEmpty()){
                        //2.1如果失败，没有未确认的订单就结束
                        break;
                    }
                    MapRecord<String, Object, Object> entries = mq.get(0);
                    //拿到键值对,'voucherId',voucherId,'userId',userId,'id',orderId
                    Map<Object, Object> value = entries.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);

                    //2.2成功就下单
                    handleVoucherOrder(voucherOrder);

                    //3.ack确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",entries.getId());
                } catch (Exception e) {
                    log.error("处理pendinglist订单异常",e);
                }
            }
        }
    }

   /* private BlockingQueue<VoucherOrder> voucherOrders= new ArrayBlockingQueue<>(1024);
    private class VoucherOrderHandeler implements Runnable{

        @Override
        public void run() {
            while (true){
                try {
                    //获取订单
                    VoucherOrder voucherOrder = voucherOrders.take();
                    //创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("处理订单异常");
                }
            }
        }*/

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //这是子线程来执行，threadlocal保存的userid在主线程，用userholder取不到userid
        Long userId = voucherOrder.getUserId();
        //以userid作为key，以线程id作为value，如果同一个用户下单多次那只有第一次成功，
        // lua脚本已经做了用户重复判断一般不会出错，这里以防万一
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = redisLock.tryLock();
        if(!isLock){
            log.error("不允许重复下单");
            return;
        }
        try {
            proxy.createSeckillVoucher(voucherOrder);
        } finally {
            redisLock.unlock();
        }
    }
    @Transactional
    public void createSeckillVoucher(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if(count>0){
            log.error("用户以购买一次");
        }
        //4.扣减库存，InnoDB引擎会自动给UPDATE、INSERT、DELETE语句添加排他锁，所以通过这样的语句可以防止超卖。
        boolean suc = seckillVoucherService.update().setSql("stock = stock-1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                //看库存是否大于0解决超卖问题
                .gt("stock",0).update();
        if(!suc){
            log.error("库存不足");
        }
        save(voucherOrder);
    }
    /**
     *@description:声明事务，方法内操作要么都发生要么都不发生，可以出现错误进行回滚。不保证对数据库操作互斥
     * ，假设先使用 select ，然后 update ，即使目标方法使用了 synchronized，但因为提交事务在@Transactional
     * 注解方法之外，即使方法执行完成了，但事务还未提交，意味着update 没有生效，若再来一个线程执行 select 就读到了脏数据。
     *
     * @param voucherId
     *
     * @return com.hmdp.dto.Result
     */

    private IVoucherOrderService proxy;
    @Override
    @Transactional
    public Result seckillVoucher(Long voucherId) {
        //1.执行lua脚本
        Long userId = UserHolder.getUser().getId();
        Long orderId = redisIdWorker.nextId("order");
        Long suc = stringRedisTemplate.execute(SECKILL_REDIS_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(),orderId.toString());

        //2.判断不为0没有购买资格
        int res = suc.intValue();
        if(res != 0){
            return Result.fail(res==1?"库存不足":"用户重复下单");
        }

        //3.有购买资格，把订单信息放到阻塞队列,如果没有订单会阻塞线程有订单就会唤醒线程
        //3.1创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        voucherOrder.setVoucherId(voucherId);
//        voucherOrder.setUserId(userId);
//
//        voucherOrder.setId(orderId);
        //3.2放入阻塞队列
//        voucherOrders.add(voucherOrder);
        //获取代理为子线程执行事务
         proxy  = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }

      /*  @Override
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
//       synchronized (userId.toString().intern()){
//           //用代理对象去处理事务，否则会事务失效
//           IVoucherOrderService proxy  = (IVoucherOrderService) AopContext.currentProxy();
//           return proxy.createSeckillVoucher(userId,voucherId);
//       }
    }*/
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
