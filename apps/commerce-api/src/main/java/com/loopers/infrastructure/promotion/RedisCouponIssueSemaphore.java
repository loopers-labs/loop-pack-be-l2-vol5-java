package com.loopers.infrastructure.promotion;

import com.loopers.application.promotion.CouponIssueLimiterProperties;
import com.loopers.application.promotion.CouponIssueSemaphore;
import com.loopers.config.redis.RedisConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link CouponIssueSemaphore} 의 Redis 구현 (DR-60). 대기열 없이 바로 성공·실패한다.
 * 프로모션마다 ZSET 하나: member 는 허가마다 새 토큰, score 는 만료 시각(ms).
 * 획득은 Lua 하나로 만료 토큰 제거 → 개수 확인 → 토큰 추가를 원자적으로 한다. 시각은 Redis TIME 이라 앱 서버 시계와 무관하다.
 * 반납은 자기 토큰만 지운다. 모든 명령은 master 로 보낸다.
 * Redis 오류는 획득 실패로 돌린다 (fail-closed). 반납 오류는 로그만 남긴다. 그 토큰은 lease 가 지나면 회수된다.
 */
@Slf4j
@Component
public class RedisCouponIssueSemaphore implements CouponIssueSemaphore {
    private static final RedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
        local t = redis.call('TIME')
        local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
        redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
        if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[1]) then
          return 0
        end
        redis.call('ZADD', KEYS[1], now + tonumber(ARGV[2]), ARGV[3])
        redis.call('PEXPIRE', KEYS[1], ARGV[2])
        return 1
        """, Long.class);

    private final RedisTemplate<String, String> masterTemplate;
    private final String permits;
    private final String leaseMillis;

    public RedisCouponIssueSemaphore(
        @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> masterTemplate,
        CouponIssueLimiterProperties properties
    ) {
        this.masterTemplate = masterTemplate;
        this.permits = String.valueOf(properties.permits());
        this.leaseMillis = String.valueOf(properties.lease().toMillis());
    }

    /** coupon-issue:semaphore:{promotionId} */
    static String key(Long promotionId) {
        return "coupon-issue:semaphore:" + promotionId;
    }

    @Override
    public Optional<String> tryAcquire(Long promotionId) {
        String token = UUID.randomUUID().toString();
        try {
            Long acquired = masterTemplate.execute(ACQUIRE, List.of(key(promotionId)), permits, leaseMillis, token);
            return Long.valueOf(1L).equals(acquired) ? Optional.of(token) : Optional.empty();
        } catch (RuntimeException e) {
            log.warn("쿠폰 발급 허가 획득 실패로 처리 (Redis 오류). [promotionId = {}]", promotionId, e);
            return Optional.empty();
        }
    }

    @Override
    public void release(Long promotionId, String token) {
        try {
            masterTemplate.opsForZSet().remove(key(promotionId), token);
        } catch (RuntimeException e) {
            log.warn("쿠폰 발급 허가 반납 실패. lease 가 지나면 회수된다. [promotionId = {}]", promotionId, e);
        }
    }
}
