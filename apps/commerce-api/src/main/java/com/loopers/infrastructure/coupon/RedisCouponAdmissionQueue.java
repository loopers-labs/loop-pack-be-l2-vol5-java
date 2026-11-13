package com.loopers.infrastructure.coupon;

import com.loopers.config.coupon.CouponAdmissionProperties;
import com.loopers.config.coupon.CouponAdmissionRedisConfig;
import com.loopers.domain.coupon.CouponAdmissionQueue;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * 접수 대기열·승격의 Redis 구현 (8주차 설계 §4·§5-1·§5-2).
 * 여러 키를 함께 바꾸는 접수·승격은 Lua, 조회·삭제는 단일 명령이다 (D6). Redis 예외는 잡지 않고 전파한다.
 */
@Component
public class RedisCouponAdmissionQueue implements CouponAdmissionQueue {

    /** KEYS = [seq, waiting, admitted, gen], ARGV = [expectedGen, acceptLimit, userId]. 반환 {code, seq} (§5-1). */
    static final String ENQUEUE_SCRIPT = """
        if redis.call('GET', KEYS[4]) ~= ARGV[1] then return {-1, 0} end
        local waiting = redis.call('ZSCORE', KEYS[2], ARGV[3])
        if waiting then return {2, tonumber(waiting)} end
        if redis.call('ZSCORE', KEYS[3], ARGV[3]) then return {2, 0} end
        local seq = tonumber(redis.call('GET', KEYS[1]) or '0')
        if seq >= tonumber(ARGV[2]) then return {0, 0} end
        seq = redis.call('INCR', KEYS[1])
        redis.call('ZADD', KEYS[2], seq, ARGV[3])
        return {1, seq}
        """;

    /** KEYS = [waiting, admitted, permits, gen], ARGV = [capacity, now, admittedTtl, permitTtl, expectedGen]. 반환 승격 수 / -1 (§5-2). */
    static final String PROMOTE_SCRIPT = """
        if redis.call('GET', KEYS[4]) ~= ARGV[5] then return -1 end
        redis.call('ZREMRANGEBYSCORE', KEYS[2], 0, ARGV[2] - ARGV[3])
        redis.call('ZREMRANGEBYSCORE', KEYS[3], 0, ARGV[2] - ARGV[4])
        local busy = redis.call('ZCARD', KEYS[2]) + redis.call('ZCARD', KEYS[3])
        local free = tonumber(ARGV[1]) - busy
        if free <= 0 then return 0 end
        local popped = redis.call('ZPOPMIN', KEYS[1], free)
        for i = 1, #popped, 2 do
          redis.call('ZADD', KEYS[2], ARGV[2], popped[i])
        end
        return #popped / 2
        """;

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> ENQUEUE = new DefaultRedisScript<>(ENQUEUE_SCRIPT, List.class);
    private static final RedisScript<Long> PROMOTE = new DefaultRedisScript<>(PROMOTE_SCRIPT, Long.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final CouponAdmissionProperties properties;
    private final Clock clock;

    public RedisCouponAdmissionQueue(
        @Qualifier(CouponAdmissionRedisConfig.COUPON_ADMISSION_REDIS_TEMPLATE) RedisTemplate<String, String> redisTemplate,
        CouponAdmissionProperties properties,
        Clock clock
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public EnqueueResult enqueue(Long campaignId, Long userId, long acceptLimit, String expectedGen) {
        List<?> result = redisTemplate.execute(
            ENQUEUE,
            List.of(
                CouponAdmissionKeys.seq(campaignId),
                CouponAdmissionKeys.waiting(campaignId),
                CouponAdmissionKeys.admitted(campaignId),
                CouponAdmissionKeys.gen(campaignId)
            ),
            expectedGen, String.valueOf(acceptLimit), String.valueOf(userId)
        );
        if (result == null || result.size() != 2 || !(result.get(0) instanceof Long code) || !(result.get(1) instanceof Long seq)) {
            throw new IllegalStateException("접수 Lua 결과를 해석할 수 없다. result=" + result);
        }
        EnqueueOutcome outcome = switch (code.intValue()) {
            case 1 -> EnqueueOutcome.ACCEPTED;
            case 2 -> EnqueueOutcome.ALREADY_ACCEPTED;
            case 0 -> EnqueueOutcome.SOLD_OUT;
            case -1 -> EnqueueOutcome.STALE_GENERATION;
            default -> throw new IllegalStateException("접수 Lua 결과 코드를 해석할 수 없다. code=" + code);
        };
        return new EnqueueResult(outcome, seq);
    }

    @Override
    public Optional<Long> waitingRank(Long campaignId, Long userId) {
        return Optional.ofNullable(redisTemplate.opsForZSet().rank(CouponAdmissionKeys.waiting(campaignId), String.valueOf(userId)));
    }

    @Override
    public boolean isAdmitted(Long campaignId, Long userId) {
        return redisTemplate.opsForZSet().score(CouponAdmissionKeys.admitted(campaignId), String.valueOf(userId)) != null;
    }

    @Override
    public long acceptedCount(Long campaignId) {
        String value = redisTemplate.opsForValue().get(CouponAdmissionKeys.seq(campaignId));
        return value == null ? 0L : Long.parseLong(value);
    }

    @Override
    public long waitingCount(Long campaignId) {
        Long size = redisTemplate.opsForZSet().size(CouponAdmissionKeys.waiting(campaignId));
        return size == null ? 0L : size;
    }

    @Override
    public long admittedCount(Long campaignId) {
        Long size = redisTemplate.opsForZSet().size(CouponAdmissionKeys.admitted(campaignId));
        return size == null ? 0L : size;
    }

    @Override
    public int promote(Long campaignId, String expectedGen) {
        Long result = redisTemplate.execute(
            PROMOTE,
            List.of(
                CouponAdmissionKeys.waiting(campaignId),
                CouponAdmissionKeys.admitted(campaignId),
                CouponAdmissionKeys.permits(campaignId),
                CouponAdmissionKeys.gen(campaignId)
            ),
            String.valueOf(properties.capacity()),
            String.valueOf(clock.millis()),
            String.valueOf(properties.admittedTtl().toMillis()),
            String.valueOf(properties.permitTtl().toMillis()),
            expectedGen
        );
        if (result == null || result < -1) {
            throw new IllegalStateException("승격 Lua 결과를 해석할 수 없다. result=" + result);
        }
        return result.intValue();
    }

    @Override
    public boolean removeAdmitted(Long campaignId, Long userId) {
        Long removed = redisTemplate.opsForZSet().remove(CouponAdmissionKeys.admitted(campaignId), String.valueOf(userId));
        return removed != null && removed > 0;
    }
}
