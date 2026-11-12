package com.loopers.infrastructure.coupon;

import com.loopers.application.coupon.CouponAdmissionProperties;
import com.loopers.application.coupon.SemaphoreGate;
import com.loopers.config.redis.RedisConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis Set 세마포어. 수용 판단은 전용 master 연결로 하고(기본 REPLICA_PREFERRED 연결의 값을 쓰지 않는다),
 * 획득은 Lua 한 번(SISMEMBER·SCARD·SADD)으로 원자적으로 한다. 키와 token은 String 직렬화다.
 */
@Slf4j
@Component
public class RedisSemaphoreGate implements SemaphoreGate {

    /** 활성 실행 token의 Set. 캐시 키와 섞지 않는다. */
    static final String PERMITS_KEY = "admission:coupon-issue:permits";

    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = acquireScript();

    private final RedisTemplate<String, String> redisTemplate;
    private final CouponAdmissionProperties properties;

    /**
     * Redis 응답을 받지 못해 Set에 남아 있을 수 있는 이 인스턴스의 token. 복구 뒤 자기 token만 SREM으로 정리하려는 기록이다(재개 단계에서 사용).
     * 시간이 지났다고 지우지 않는다.
     */
    private final Set<String> unreleasedTokens = ConcurrentHashMap.newKeySet();

    public RedisSemaphoreGate(
        @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redisTemplate,
        CouponAdmissionProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public Admission acquire() {
        String token = properties.instanceId() + ":" + UUID.randomUUID();
        return acquire(token);
    }

    Admission acquire(String token) {
        int effectiveCapacity = effectiveCapacity();
        if (effectiveCapacity < 1) {
            // 유효 용량이 없으면 Redis를 호출하지 않고 자리 없음으로 처리한다.
            return new Admission(Status.FULL, token);
        }
        try {
            Long result = redisTemplate.execute(ACQUIRE_SCRIPT, List.of(PERMITS_KEY), String.valueOf(effectiveCapacity), token);
            if (result != null && result == 1L) {
                return new Admission(Status.ACQUIRED, token);
            }
            if (result != null && result == 0L) {
                return new Admission(Status.FULL, token);
            }
            return uncertain(token, null);
        } catch (RuntimeException e) {
            return uncertain(token, e);
        }
    }

    @Override
    public void release(Admission admission) {
        if (admission.status() == Status.FULL) {
            return;
        }
        String token = admission.token();
        try {
            redisTemplate.opsForSet().remove(PERMITS_KEY, token);
            unreleasedTokens.remove(token);
        } catch (RuntimeException e) {
            // 쿠폰과 발급 수는 되돌리지 않는다. 자기 token만 기록해 두고 복구 뒤 정리한다.
            unreleasedTokens.add(token);
            log.warn("자리 반환 실패, 복구 뒤 정리할 token으로 기록한다: token={}, error={}", token, e.getMessage());
        }
    }

    /** 현재 활성 token 수(점유). 읽지 못하면 0이 아니라 예외를 그대로 올린다. */
    public long activeCount() {
        Long size = redisTemplate.opsForSet().size(PERMITS_KEY);
        if (size == null) {
            throw new IllegalStateException("활성 token 수를 읽지 못했다");
        }
        return size;
    }

    /** 반환하지 못해 기록해 둔 이 인스턴스의 token. */
    public Set<String> unreleasedTokens() {
        return Set.copyOf(unreleasedTokens);
    }

    private static DefaultRedisScript<Long> acquireScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/coupon-admission-acquire.lua"));
        script.setResultType(Long.class);
        return script;
    }

    private int effectiveCapacity() {
        return properties.capacity();
    }

    private Admission uncertain(String token, RuntimeException cause) {
        // 응답을 못 받았어도 token이 Set에 들어갔을 수 있다. 자기 token을 기록해 둔다.
        unreleasedTokens.add(token);
        log.warn("세마포어 획득 결과를 알 수 없다: token={}, error={}", token, cause == null ? "null result" : cause.getMessage());
        return new Admission(Status.UNCERTAIN, token);
    }
}
