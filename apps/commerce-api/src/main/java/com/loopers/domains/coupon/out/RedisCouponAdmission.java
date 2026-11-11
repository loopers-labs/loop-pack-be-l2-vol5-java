package com.loopers.domains.coupon.out;

import com.loopers.domains.coupon.app.port.AdmissionUnavailableException;
import com.loopers.domains.coupon.app.port.CouponAdmission;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

// 수용 자리는 Redis Set 하나(키 coupon:issue:permits, D135)에 token으로 둔다. 서버 메모리에 두지 않아 여러 서버에서 같은 자리를 본다.
// Redis·Spring 예외와 0·1이 아닌 결과는 모두 AdmissionUnavailableException으로 바꾼다(D123).
@Component
public class RedisCouponAdmission implements CouponAdmission {
    public static final String KEY = "coupon:issue:permits";
    public static final String SCRIPT = "redis/coupon-admission-acquire.lua";
    private static final RedisScript<Long> ACQUIRE = RedisScript.of(new ClassPathResource(SCRIPT), Long.class);

    private final RedisTemplate<String, String> redisTemplate;

    public RedisCouponAdmission(@Qualifier("couponAdmissionRedisTemplate") RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean acquire(String token, int capacity) {
        Long result;
        try {
            result = redisTemplate.execute(ACQUIRE, List.of(KEY), String.valueOf(capacity), token);
        } catch (RuntimeException e) {
            throw new AdmissionUnavailableException("수용 자리 획득 실패: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
        if (result != null && result == 1L) {
            return true;
        }
        if (result != null && result == 0L) {
            return false;
        }
        throw new AdmissionUnavailableException("수용 자리 획득 결과가 0·1이 아님: " + result);
    }

    @Override
    public void release(String token) {
        try {
            redisTemplate.opsForSet().remove(KEY, token);
        } catch (RuntimeException e) {
            throw new AdmissionUnavailableException("수용 자리 반환 실패: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }
}
