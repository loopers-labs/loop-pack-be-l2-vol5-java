package com.loopers.infrastructure.coupon;

import com.loopers.config.coupon.CouponAdmissionProperties;
import com.loopers.config.coupon.CouponAdmissionRedisConfig;
import com.loopers.domain.coupon.CouponPermitSemaphore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.RedisZSetCommands.ZAddArgs;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;

/**
 * permits ZSET 기반 세마포어의 Redis 구현 (8주차 설계 §5-3·§7-2, D4).
 * 자리 획득만 Lua 이고 하트비트(ZADD XX)·반납(ZREM)은 단일 명령이다. Redis 예외는 잡지 않고 전파한다.
 */
@Component
public class RedisCouponPermitSemaphore implements CouponPermitSemaphore {

    /** KEYS = [admitted, permits, gen], ARGV = [capacity, now, permitTtl, token, userId, expectedGen]. 반환 1/0/-1/-2 (§5-3). */
    static final String ACQUIRE_SCRIPT = """
        if redis.call('GET', KEYS[3]) ~= ARGV[6] then return -1 end
        redis.call('ZREMRANGEBYSCORE', KEYS[2], 0, ARGV[2] - ARGV[3])
        if not redis.call('ZSCORE', KEYS[1], ARGV[5]) then return -2 end
        if redis.call('ZSCORE', KEYS[2], ARGV[4]) then return 1 end
        if redis.call('ZCARD', KEYS[2]) >= tonumber(ARGV[1]) then return 0 end
        redis.call('ZADD', KEYS[2], ARGV[2], ARGV[4])
        return 1
        """;

    private static final RedisScript<Long> ACQUIRE = new DefaultRedisScript<>(ACQUIRE_SCRIPT, Long.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final CouponAdmissionProperties properties;
    private final Clock clock;

    public RedisCouponPermitSemaphore(
        @Qualifier(CouponAdmissionRedisConfig.COUPON_ADMISSION_REDIS_TEMPLATE) RedisTemplate<String, String> redisTemplate,
        CouponAdmissionProperties properties,
        Clock clock
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public ClaimOutcome tryAcquire(Long campaignId, Long userId, String token, String expectedGen) {
        Long result = redisTemplate.execute(
            ACQUIRE,
            List.of(
                CouponAdmissionKeys.admitted(campaignId),
                CouponAdmissionKeys.permits(campaignId),
                CouponAdmissionKeys.gen(campaignId)
            ),
            String.valueOf(properties.capacity()),
            String.valueOf(clock.millis()),
            String.valueOf(properties.permitTtl().toMillis()),
            token,
            String.valueOf(userId),
            expectedGen
        );
        if (result == null) {
            throw new IllegalStateException("세마포어 Lua 결과를 해석할 수 없다. result=null");
        }
        return switch (result.intValue()) {
            case 1 -> ClaimOutcome.ACQUIRED;
            case 0 -> ClaimOutcome.NO_PERMIT;
            case -1 -> ClaimOutcome.STALE_GENERATION;
            case -2 -> ClaimOutcome.NOT_ADMITTED;
            default -> throw new IllegalStateException("세마포어 Lua 결과 코드를 해석할 수 없다. code=" + result);
        };
    }

    /**
     * ZADD XX CH. XX 는 있는 token 만 갱신해 회수된 token 을 되살리지 않고, CH 는 "갱신된 수"를 돌려주게 해 성공 여부를 알 수 있게 한다.
     */
    @Override
    public boolean heartbeat(Long campaignId, String token) {
        byte[] key = CouponAdmissionKeys.permits(campaignId).getBytes(StandardCharsets.UTF_8);
        byte[] member = token.getBytes(StandardCharsets.UTF_8);
        double now = clock.millis();
        Boolean updated = redisTemplate.execute(
            connection -> connection.zSetCommands().zAdd(key, now, member, ZAddArgs.ifExists().ch()),
            true
        );
        return Boolean.TRUE.equals(updated);
    }

    @Override
    public boolean release(Long campaignId, String token) {
        Long removed = redisTemplate.opsForZSet().remove(CouponAdmissionKeys.permits(campaignId), token);
        return removed != null && removed > 0;
    }
}
