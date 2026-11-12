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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
class RedisCouponAdmission implements CouponAdmission {

    static final String KEY = "coupon:issue:admission:v1";
    static final String EPOCH_KEY = "coupon:issue:admission:epoch:v1";

    private static final long STATE_LOST = -1L;
    private static final int ATTEMPTS = 2;
    private static final RedisScript<Long> ACQUIRE =
        RedisScript.of(new ClassPathResource("redis/coupon-admission-acquire.lua"), Long.class);

    private final RedisTemplate<String, String> redis;
    private final int capacity;
    private final AtomicReference<String> epoch = new AtomicReference<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Set<String> unreleased = ConcurrentHashMap.newKeySet();

    RedisCouponAdmission(
        @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redis,
        CouponAdmissionProperties properties
    ) {
        this.redis = redis;
        this.capacity = properties.capacity();
    }

    @Override
    public Result tryAcquire() {
        String current = epoch.get();
        if (current == null) {
            return new Result.Unavailable();
        }
        String token = UUID.randomUUID().toString();
        Long admitted = null;
        for (int attempt = 1; attempt <= ATTEMPTS && admitted == null; attempt++) {
            try {
                admitted = redis.execute(ACQUIRE, List.of(KEY, EPOCH_KEY), token, String.valueOf(capacity), current);
            } catch (RuntimeException e) {
                log.warn("쿠폰 수용 자리를 확인하지 못했습니다: attempt={}, token={}", attempt, token, e);
            }
        }
        if (admitted == null) {
            unreleased.add(token);
            closeBecause(current, "자리 획득 결과를 알 수 없습니다: token=" + token);
            return new Result.Unavailable();
        }
        if (admitted == 1L) {
            inFlight.add(token);
            return new Result.Admitted(token);
        }
        if (admitted == 0L) {
            return new Result.Full();
        }
        if (admitted == STATE_LOST) {
            closeBecause(current, "Redis 의 수용 상태가 이 서버가 연 것과 다릅니다");
        }
        return new Result.Unavailable();
    }

    @Override
    public void release(String token) {
        inFlight.remove(token);
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                redis.opsForSet().remove(KEY, token);
                return;
            } catch (RuntimeException e) {
                log.warn("쿠폰 수용 자리를 반환하지 못했습니다: attempt={}, token={}", attempt, token, e);
            }
        }
        unreleased.add(token);
        closeBecause(epoch.get(), "자리 반환 결과를 알 수 없습니다: token=" + token);
    }

    @Override
    public Opened open() {
        String next = UUID.randomUUID().toString();
        redis.opsForValue().set(EPOCH_KEY, next);
        epoch.set(next);
        return new Opened(next, 0);
    }

    @Override
    public void close() {
        epoch.set(null);
    }

    @Override
    public boolean isOpen() {
        return epoch.get() != null;
    }

    private void closeBecause(String current, String reason) {
        if (current != null && epoch.compareAndSet(current, null)) {
            log.error("쿠폰 수용을 닫습니다: epoch={}, {}", current, reason);
        }
    }
}
