package com.loopers.coupon.infrastructure;

import com.loopers.config.redis.RedisConfig;
import com.loopers.coupon.application.CouponAdmission;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class RedisCouponAdmission implements CouponAdmission {

    private static final String PERMITS_KEY = "coupon:issue:permits";
    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = acquireScript();

    private final RedisTemplate<String, String> redis;
    private final CouponAdmissionProperties properties;
    private final AtomicBoolean accepting;

    public RedisCouponAdmission(
        @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redis,
        CouponAdmissionProperties properties
    ) {
        this.redis = redis;
        this.properties = properties;
        this.accepting = new AtomicBoolean(properties.enabled());
    }

    @Override
    public Optional<String> acquire() {
        if (!accepting.get()) {
            throw new CoreException(ErrorCode.COUPON_ADMISSION_UNAVAILABLE);
        }
        String token = UUID.randomUUID().toString();
        Long acquired;
        try {
            acquired = redis.execute(ACQUIRE_SCRIPT, List.of(PERMITS_KEY),
                String.valueOf(properties.capacity()), token);
        } catch (RuntimeException exception) {
            accepting.set(false);
            throw new CoreException(ErrorCode.COUPON_ADMISSION_UNAVAILABLE);
        }
        if (Long.valueOf(1L).equals(acquired)) {
            return Optional.of(token);
        }
        if (Long.valueOf(0L).equals(acquired)) {
            return Optional.empty();
        }
        accepting.set(false);
        throw new CoreException(ErrorCode.COUPON_ADMISSION_UNAVAILABLE);
    }

    @Override
    public void release(String token) {
        try {
            redis.opsForSet().remove(PERMITS_KEY, token);
        } catch (RuntimeException exception) {
            accepting.set(false);
            throw new CoreException(ErrorCode.COUPON_ADMISSION_UNAVAILABLE);
        }
    }

    private static DefaultRedisScript<Long> acquireScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/coupon-admission-acquire.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
