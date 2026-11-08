package com.loopers.infrastructure.coupon;

import com.loopers.config.redis.RedisConfig;
import com.loopers.domain.coupon.CouponAdmission;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Slf4j
@Component
class RedisCouponAdmission implements CouponAdmission {

    static final String KEY = "coupon:issue:admission:v1";

    private static final RedisScript<Long> ACQUIRE =
        RedisScript.of(new ClassPathResource("redis/coupon-admission-acquire.lua"), Long.class);

    private final RedisTemplate<String, String> redis;
    private final int capacity;

    RedisCouponAdmission(
        @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redis,
        CouponAdmissionProperties properties
    ) {
        this.redis = redis;
        this.capacity = properties.capacity();
    }

    @Override
    public Result tryAcquire() {
        String token = UUID.randomUUID().toString();
        Long admitted;
        try {
            admitted = redis.execute(ACQUIRE, List.of(KEY), token, String.valueOf(capacity));
        } catch (RuntimeException e) {
            log.warn("쿠폰 수용 자리를 확인하지 못했습니다", e);
            return new Result.Unavailable();
        }
        if (admitted == null) {
            return new Result.Unavailable();
        }
        if (admitted == 1L) {
            return new Result.Admitted(token);
        }
        if (admitted == 0L) {
            return new Result.Full();
        }
        return new Result.Unavailable();
    }

    @Override
    public void release(String token) {
        try {
            redis.opsForSet().remove(KEY, token);
        } catch (RuntimeException e) {
            log.error("쿠폰 수용 자리를 반환하지 못했습니다: token={}", token, e);
        }
    }
}
