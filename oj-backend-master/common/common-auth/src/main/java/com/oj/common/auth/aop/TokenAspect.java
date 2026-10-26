package com.oj.common.auth.aop;

import javax.annotation.Resource;

import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.oj.common.auth.utils.TokenUtils;
import com.oj.common.core.enums.HttpCodeEnum;
import com.oj.common.core.exception.BusinessException;
import com.oj.common.redis.constant.RedisConstant;

import cn.hutool.core.convert.NumberWithFormat;
import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTException;

@Component
@Aspect
@Order(1)
public class TokenAspect {
    @Value("${oj.user.secret-key}")
    private String secretKey;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Before("@annotation(com.oj.common.auth.annotation.TokenCheck)")
    public void doBefore() {
        // 从Cookie中获取Token
        String token = TokenUtils.getToken();

        if (token == null) {
            throw new BusinessException(HttpCodeEnum.NOT_LOGIN);
        }

        JWT jwt = JWT.of(token);
        // 验证Token是否有效
        try {
            boolean validate = jwt.setKey(secretKey.getBytes()).validate(0L);
            if (!validate) {
                throw new BusinessException(HttpCodeEnum.NO_AUTH, "无效的TOKEN");
            }
        } catch (JWTException e) {
            throw new BusinessException(HttpCodeEnum.NO_AUTH, "TOKEN验证失败");
        }

        // 验证Token是否和Redis中一致
        Long uid = (Long) ((NumberWithFormat) jwt.getPayload("uid")).getNumber();
        String redisToken = stringRedisTemplate.opsForValue().get(RedisConstant.USER_TOKEN_PREFIX + uid);
        if (!token.equals(redisToken)) {
            throw new BusinessException(HttpCodeEnum.NO_AUTH, "无效的TOKEN");
        }
    }
}
