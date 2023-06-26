package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;


/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result sendCode(String phone) {
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("phone invalid");
        }
        String validCode = RandomUtil.randomNumbers(6);
        //保存验证码到redis
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY +phone,validCode,RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //发送验证码
        log.debug("validCode:"+validCode);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginFormDTO) {
        //1.校验手机号
        String phone = loginFormDTO.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("phone invalid");
        }
        // 2.从redis获取code校验
        String cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        String code = loginFormDTO.getCode();
        if(cacheCode==null||!code.equals(cacheCode)){
            return Result.fail("code is null or not correct");
        }
        //3.一致，查询用户
        User user = query().eq("phone", phone).one();
        //4.用户不存在创建并保存
        if(user==null){
            user = createUserWithPhone(phone);
        }
        //5.保存用户到redis
        String token = UUID.randomUUID().toString(true);
        //5.1 将user转换dto再转hashmap存储，putall存储map所有user属性和值
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //把user的id转化成string，才能让stringRedisTemplate存储
        Map<String, Object> stringObjectMap = BeanUtil.beanToMap(userDTO,new HashMap<>(), CopyOptions.create()
                .setIgnoreNullValue(true)
                .setFieldValueEditor((name,value)->value.toString())
        );
        stringRedisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY+token,stringObjectMap);
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY+token,30,TimeUnit.MINUTES);
        //返回token给前端，保存在请求头
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX+RandomUtil.randomNumbers(10));
        save(user);
        return user;
    }
}
