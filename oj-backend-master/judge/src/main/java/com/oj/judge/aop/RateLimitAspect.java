package com.oj.judge.aop;

import java.util.concurrent.TimeUnit;

import javax.annotation.Resource;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.oj.common.auth.utils.TokenUtils;
import com.oj.common.core.enums.HttpCodeEnum;
import com.oj.common.core.exception.BusinessException;
import com.oj.judge.annotation.RateLimit;

@Aspect
@Component
@Order(3)
public class RateLimitAspect {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 压测/基准测试可临时关闭，生产默认开启
    @Value("${oj.rate-limit.enabled:true}")
    private boolean enabled;

    @Before("@annotation(rateLimit)")
    public void doBefore(JoinPoint point, RateLimit rateLimit) {
        if (!enabled) {
            return;
        }
        String key = rateLimit.key();
        if (key.isEmpty()) {
            MethodSignature signature = (MethodSignature) point.getSignature();
            key = "rate_limit:" + signature.getDeclaringTypeName() + "." + signature.getMethod().getName();
        } 
        // 加上用户ID
        Long uid = TokenUtils.getCurrentUid();
        key = key + ":" + uid;

        // 使用Redis的INCR+EXPIRE实现限流
        Long count = stringRedisTemplate.opsForValue().increment(key);
        if (count > 1) {
            throw new BusinessException(HttpCodeEnum.TOO_MANY_REQUESTS, rateLimit.message());
        }
        stringRedisTemplate.expire(key, rateLimit.rateInterval(), TimeUnit.SECONDS);
    }
}
