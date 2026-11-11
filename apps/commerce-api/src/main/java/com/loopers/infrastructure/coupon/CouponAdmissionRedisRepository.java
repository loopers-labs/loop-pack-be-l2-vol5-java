package com.loopers.infrastructure.coupon;

import com.loopers.config.redis.RedisConfig;
import com.loopers.domain.coupon.CouponAdmissionRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 전용 Set 하나에 실행별 token 을 두는 수용 자리. 기존 master template 만 사용하며 TTL 을 두지 않는다.
 * 1/0 이외의 응답(null 포함)은 획득·반환 결과로 해석하지 않고 예외로 올린다.
 */
@Component
public class CouponAdmissionRedisRepository implements CouponAdmissionRepository {

    public static final String PERMITS_KEY = "commerce:coupon-admission:v1:permits";

    private static final RedisScript<Long> ACQUIRE_SCRIPT =
        RedisScript.of(new ClassPathResource("redis/coupon-admission-acquire.lua"), Long.class);

    private final RedisTemplate<String, String> redisTemplate;

    public CouponAdmissionRedisRepository(
        @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redisTemplate
    ) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Acquisition tryAcquire(String token, int capacity) {
        Long result = redisTemplate.execute(ACQUIRE_SCRIPT, List.of(PERMITS_KEY), token, String.valueOf(capacity));
        if (result == null) {
            throw new IllegalStateException("수용 자리 획득 응답이 없습니다. [token = " + token + "]");
        }
        if (result == 1L) {
            return Acquisition.ACQUIRED;
        }
        if (result == 0L) {
            return Acquisition.FULL;
        }
        throw new IllegalStateException("수용 자리 획득 응답이 1/0 이 아닙니다. [token = " + token + ", result = " + result + "]");
    }

    @Override
    public long release(String token) {
        Long removed = redisTemplate.opsForSet().remove(PERMITS_KEY, token);
        if (removed == null || removed < 0 || removed > 1) {
            throw new IllegalStateException("수용 자리 반환 응답이 0/1 이 아닙니다. [token = " + token + ", removed = " + removed + "]");
        }
        return removed;
    }
}
