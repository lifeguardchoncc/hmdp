package com.hmdp.config;

import com.hmdp.utils.RefreshTokenInterceptor;
import com.hmdp.utils.loginInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

/**
 * @description:登录校验拦截哪些请求
 * @author: lyl
 * @time: 2023/6/9 11:54
 */
@Configuration
public class MvcConfig implements WebMvcConfigurer {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //有些请求比如查看用户要做登录校验，排除不校验的请求
        registry.addInterceptor(new loginInterceptor()).excludePathPatterns(
            "/shop/**",
            "/shop-type/**",
                "/voucher/**",
                "/upload/**",
                "/blog/hot",
                "/user/code",
                "/user/login"
        ).order(1);
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).order(0);
    }
}
