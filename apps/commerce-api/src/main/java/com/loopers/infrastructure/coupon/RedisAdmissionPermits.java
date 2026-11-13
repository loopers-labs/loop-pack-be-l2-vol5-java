package com.loopers.infrastructure.coupon;

import com.loopers.application.coupon.AdmissionPermits;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 동시 처리 자리 장부의 Redis 구현(docs/week8/decisions.md D-3, D-4, D-5, D-14, D-16).
 * token Set 하나({@value #PERMITS_KEY})에 실행마다 다른 token을 넣고 뺀다. TTL은 걸지 않는다(D-5).
 * 오류·timeout은 바꾸지 않고 그대로 던진다 — 닫을지는 {@link com.loopers.application.coupon.CouponAdmission}이 정한다.
 */
@Component
public class RedisAdmissionPermits implements AdmissionPermits {

    public static final String PERMITS_KEY = "coupon-issue:permits";
    private static final long ADMITTED = 1L;
    private static final RedisScript<Long> ACQUIRE_SCRIPT =
        RedisScript.of(new ClassPathResource("redis/coupon-admission-acquire.lua"), Long.class);

    private final RedisTemplate<String, String> redisTemplate;

    public RedisAdmissionPermits(@Qualifier(AdmissionRedisConfig.ADMISSION_REDIS_TEMPLATE) RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean tryAcquire(String token, int capacity) {
        // EVALSHA 한 번(스크립트가 없으면 EVAL). 확인과 추가가 Redis 안에서 한 번에 실행된다.
        Long result = redisTemplate.execute(ACQUIRE_SCRIPT, List.of(PERMITS_KEY), token, String.valueOf(capacity));
        if (result == null) {
            throw new IllegalStateException("자리 확인 응답이 비었다 token=" + token);
        }
        return result == ADMITTED;
    }

    @Override
    public void release(String token) {
        redisTemplate.opsForSet().remove(PERMITS_KEY, token);
    }

    @Override
    public Set<String> holders() {
        Set<String> members = redisTemplate.opsForSet().members(PERMITS_KEY);
        return members == null ? Set.of() : Set.copyOf(members);
    }
}
