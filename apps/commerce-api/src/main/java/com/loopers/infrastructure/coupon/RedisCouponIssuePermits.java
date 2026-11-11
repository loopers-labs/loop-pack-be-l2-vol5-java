package com.loopers.infrastructure.coupon;

import com.loopers.application.coupon.CouponIssuePermits;
import com.loopers.application.coupon.CouponPermitScriptException;
import com.loopers.config.redis.RedisConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RedisCouponIssuePermits implements CouponIssuePermits {

    public static final String PERMITS_KEY = "coupon:issue:permits";

    private final RedisTemplate<String, String> redisTemplate;
    private final DefaultRedisScript<Long> acquireScript;

    public RedisCouponIssuePermits(
        @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redisTemplate
    ) {
        this.redisTemplate = redisTemplate;
        this.acquireScript = new DefaultRedisScript<>();
        this.acquireScript.setLocation(new ClassPathResource("redis/coupon-issue-acquire.lua"));
        this.acquireScript.setResultType(Long.class);
    }

    @Override
    public boolean tryAcquire(String token, int capacity) {
        try {
            Long acquired = redisTemplate.execute(acquireScript, List.of(PERMITS_KEY),
                Integer.toString(capacity), token);
            if (acquired == null || (acquired != 0L && acquired != 1L)) {
                throw new CouponPermitScriptException(null);
            }
            return acquired == 1L;
        } catch (CouponPermitScriptException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw translate(exception);
        }
    }

    @Override
    public long release(String token) {
        try {
            Long removed = redisTemplate.opsForSet().remove(PERMITS_KEY, token);
            if (removed == null || removed < 0L) {
                throw new CouponPermitScriptException(null);
            }
            return removed;
        } catch (CouponPermitScriptException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw translate(exception);
        }
    }

    @Override
    public boolean isEmpty() {
        try {
            Long size = redisTemplate.opsForSet().size(PERMITS_KEY);
            if (size == null || size < 0L) {
                throw new CouponPermitScriptException(null);
            }
            return size == 0L;
        } catch (CouponPermitScriptException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw translate(exception);
        }
    }

    private RuntimeException translate(RuntimeException exception) {
        return RedisCouponPermitExceptionTranslator.translate(exception);
    }
}
