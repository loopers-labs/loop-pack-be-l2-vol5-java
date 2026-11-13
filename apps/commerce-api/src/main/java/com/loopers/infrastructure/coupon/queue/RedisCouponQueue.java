package com.loopers.infrastructure.coupon.queue;

import com.loopers.application.coupon.queue.CouponQueueInconsistentStateException;
import com.loopers.application.coupon.queue.CouponQueueWorkerState;
import com.loopers.application.coupon.queue.CouponRequestQueue;
import com.loopers.config.redis.RedisConfig;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Redis waiting and processing records use separate structures; only waiting is bounded. */
@Component
public class RedisCouponQueue implements CouponRequestQueue {
    private static final RedisScript<String> SUBMIT = script("redis/queue-submit.lua", String.class);
    private static final RedisScript<String> CLAIM = script("redis/queue-claim.lua", String.class);
    private static final RedisScript<String> BEGIN_ATTEMPT = script("redis/queue-begin-attempt.lua", String.class);
    private static final RedisScript<String> SCHEDULE_RETRY = script("redis/queue-schedule-retry.lua", String.class);
    private static final RedisScript<Long> COMPLETE = script("redis/queue-complete.lua", Long.class);
    private static final RedisScript<Long> RETRY = script("redis/queue-retry.lua", Long.class);

    private final RedisTemplate<String, String> redis;
    private final CouponQueueWorkerState state;
    private final int capacity;
    private final String waitingKey;
    private final String requestsKey;
    private final String usersKey;
    private final String processingKey;
    private final String campaignsKey;

    public RedisCouponQueue(
            @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redis,
            @Value("${coupon.queue.capacity:100}") int capacity,
            @Value("${coupon.queue.key:}") String prefix,
            CouponQueueWorkerState state) {
        if (capacity < 1 || prefix == null) {
            throw new IllegalArgumentException("쿠폰 대기열 설정이 올바르지 않습니다.");
        }
        this.redis = redis;
        this.state = state;
        this.capacity = capacity;
        String keyPrefix = prefix.isBlank() ? "coupon:{issue}:queue" : prefix;
        this.waitingKey = keyPrefix + ":waiting";
        this.requestsKey = keyPrefix + ":requests";
        this.usersKey = keyPrefix + ":users";
        this.processingKey = keyPrefix + ":processing";
        this.campaignsKey = keyPrefix + ":campaigns";
    }

    @Override
    public SubmitResult submit(long campaignId, long userId) {
        if (campaignId <= 0 || userId <= 0) {
            throw new IllegalArgumentException("캠페인과 사용자 ID는 양수여야 합니다.");
        }
        return state.withAdmissionLock(() -> submitSerialized(campaignId, userId));
    }

    private SubmitResult submitSerialized(long campaignId, long userId) {
        String result;
        try {
            result = redis.execute(SUBMIT, List.of(waitingKey, requestsKey, usersKey, campaignsKey, processingKey),
                    Long.toString(campaignId), Long.toString(userId),
                    UUID.randomUUID().toString(), Integer.toString(capacity),
                    state.acceptingNewRequests() ? "1" : "0");
        } catch (RuntimeException failure) {
            throw unavailable(failure);
        }
        if (result == null) {
            throw unavailable();
        }
        String[] fields = result.split(":", 2);
        try {
            return new SubmitResult(SubmitOutcome.valueOf(fields[0]), fields.length == 2 ? fields[1] : null);
        } catch (IllegalArgumentException failure) {
            throw unavailable(failure);
        }
    }

    /** Called only after the DB has confirmed that the campaign is sold out. */
    @Override
    public void markSoldOut(long campaignId) {
        if (campaignId <= 0) {
            throw new IllegalArgumentException("캠페인 ID는 양수여야 합니다.");
        }
        try {
            redis.opsForHash().put(campaignsKey, Long.toString(campaignId), "SOLD_OUT");
        } catch (RuntimeException failure) {
            throw unavailable(failure);
        }
    }

    @Override
    public Optional<Claim> claim(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("작업자 ID를 확인해 주세요.");
        }
        String owner = workerId + ":" + UUID.randomUUID();
        final String result;
        try {
            result = redis.execute(CLAIM, List.of(waitingKey, requestsKey, processingKey), owner);
        } catch (RuntimeException failure) {
            throw unavailable(failure);
        }
        if (result == null) {
            throw unavailable();
        }
        if (result.isEmpty()) {
            return Optional.empty();
        }
        String[] fields = result.split("\\|", -1);
        if (fields.length != 8) {
            throw unavailable();
        }
        try {
            int attemptCount = Integer.parseInt(fields[3]);
            long nextMillis = Long.parseLong(fields[4]);
            long redisNowMillis = Long.parseLong(fields[7]);
            if (attemptCount < 0 || attemptCount > 4 || nextMillis < 0 || redisNowMillis < 0) {
                throw unavailable();
            }
            long remaining = Math.max(0, nextMillis - redisNowMillis);
            boolean exhausted = booleanField(fields[5]);
            if (booleanField(fields[6])) {
                // A previous execution must be reconciled before it can be consumed again.
                throw unavailable();
            }
            return Optional.of(new Claim(fields[0], Long.parseLong(fields[1]), Long.parseLong(fields[2]), owner,
                    remaining, exhausted));
        } catch (NumberFormatException failure) {
            throw unavailable(failure);
        }
    }

    @Override
    public void complete(Claim claim) {
        final Long result;
        try {
            result = redis.execute(COMPLETE, List.of(requestsKey, processingKey, usersKey),
                    claim.requestId(), claim.workerId(), claim.campaignId() + ":" + claim.userId());
        } catch (RuntimeException failure) {
            throw unavailable(failure);
        }
        if (result != null && result == -1L) {
            state.pause();
            throw new CouponQueueInconsistentStateException();
        }
        if (result == null || result != 1L) {
            throw unavailable();
        }
    }

    @Override
    public AttemptReservation reserveAttempt(Claim claim) {
        String result;
        try {
            result = redis.execute(BEGIN_ATTEMPT, List.of(requestsKey, processingKey),
                    claim.requestId(), claim.workerId());
        } catch (RuntimeException failure) {
            throw unavailable(failure);
        }
        if (result == null) {
            throw unavailable();
        }
        String[] fields = result.split("\\|", -1);
        if (fields.length != 4) {
            throw unavailable();
        }
        try {
            int count = Integer.parseInt(fields[1]);
            if (count < 0 || count > 4) {
                throw unavailable();
            }
            long remaining = Math.max(0, Long.parseLong(fields[2]) - Long.parseLong(fields[3]));
            return switch (fields[0]) {
                case "RESERVED" -> new AttemptReservation(false, 0, count);
                case "WAIT" -> {
                    if (remaining == 0) {
                        throw unavailable();
                    }
                    yield new AttemptReservation(false, remaining, count);
                }
                case "EXHAUSTED" -> new AttemptReservation(true, 0, count);
                default -> throw unavailable();
            };
        } catch (NumberFormatException failure) {
            throw unavailable(failure);
        }
    }

    @Override
    public RetrySchedule scheduleRetry(Claim claim) {
        String result;
        try {
            result = redis.execute(SCHEDULE_RETRY, List.of(requestsKey, processingKey),
                    claim.requestId(), claim.workerId());
        } catch (RuntimeException failure) {
            throw unavailable(failure);
        }
        if (result == null) {
            throw unavailable();
        }
        String[] fields = result.split("\\|", -1);
        if (fields.length != 4) {
            throw unavailable();
        }
        try {
            boolean exhausted = booleanField(fields[2]);
            long remaining = Math.max(0, Long.parseLong(fields[1]) - Long.parseLong(fields[3]));
            return new RetrySchedule(exhausted, remaining);
        } catch (NumberFormatException failure) {
            throw unavailable(failure);
        }
    }

    @Override
    public boolean retry(Claim claim) {
        final Long result;
        try {
            result = redis.execute(RETRY, List.of(waitingKey, requestsKey, processingKey),
                    claim.requestId(), claim.workerId(), Integer.toString(capacity));
        } catch (RuntimeException failure) {
            throw unavailable(failure);
        }
        if (result == null) {
            throw unavailable();
        }
        if (result != 0L && result != 1L) {
            throw new CoreException(ErrorType.COUPON_ADMISSION_UNAVAILABLE);
        }
        return result == 1L;
    }

    private boolean booleanField(String value) {
        if ("1".equals(value)) {
            return true;
        }
        if ("0".equals(value)) {
            return false;
        }
        throw unavailable();
    }

    private CoreException unavailable() {
        state.pause();
        return new CoreException(ErrorType.COUPON_ADMISSION_UNAVAILABLE);
    }

    private CoreException unavailable(Throwable cause) {
        CoreException failure = unavailable();
        failure.initCause(cause);
        return failure;
    }

    private static <T> RedisScript<T> script(String path, Class<T> type) {
        var script = new DefaultRedisScript<T>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(type);
        return script;
    }
}
