package com.loopers.domains.coupon.out;

import com.loopers.domains.coupon.app.port.CouponAdmission;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisCouponAdmission implements CouponAdmission {
    public static final String KEY = "coupon:issue:permits";
    public static final String SCRIPT = "redis/coupon-admission-acquire.lua";

    private final RedisTemplate<String, String> redisTemplate;

    public RedisCouponAdmission(@Qualifier("couponAdmissionRedisTemplate") RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean acquire(String token, int capacity) {
        return true;
    }

    @Override
    public void release(String token) {
    }
}
