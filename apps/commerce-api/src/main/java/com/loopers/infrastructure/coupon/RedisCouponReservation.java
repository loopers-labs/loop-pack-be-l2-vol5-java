package com.loopers.infrastructure.coupon;

import com.loopers.config.redis.RedisConfig;
import com.loopers.domain.coupon.CouponReservation;
import com.loopers.domain.coupon.IssueQuota;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

@Component
class RedisCouponReservation implements CouponReservation {

    private static final long RESERVED = 1L;
    private static final long EXISTING = 0L;
    private static final long SOLD_OUT = -1L;
    private static final long LOST = -2L;
    private static final long CLOSED = -3L;
    private static final String READY = "__ready";
    private static final int READY_SCORE = 0;

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> RESERVE = RedisScript.of(read("redis/coupon-reservation-reserve.lua"), List.class);

    private final RedisTemplate<String, String> redis;

    RedisCouponReservation(@Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redis) {
        this.redis = redis;
    }

    private static String read(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String key(Long campaignId) {
        return "coupon:campaign:" + campaignId + ":issued";
    }

    static String closedKey(Long campaignId) {
        return "coupon:campaign:" + campaignId + ":closed";
    }

    @Override
    public boolean isReserved(Long campaignId, Long userId) {
        return redis.opsForZSet().score(key(campaignId), String.valueOf(userId)) != null;
    }

    @Override
    public boolean isClosed(Long campaignId) {
        return Boolean.TRUE.equals(redis.hasKey(closedKey(campaignId)));
    }

    @Override
    public void close(Long campaignId) {
        redis.opsForValue().set(closedKey(campaignId), "1");
    }

    @Override
    public void open(Long campaignId) {
        redis.delete(closedKey(campaignId));
    }

    @Override
    public Result reserve(Long campaignId, Long userId, IssueQuota quota, int dbIssuedCount) {
        List<?> reply = redis.execute(RESERVE, List.of(key(campaignId), closedKey(campaignId)),
            String.valueOf(userId), String.valueOf(quota.value()), String.valueOf(dbIssuedCount), READY);
        long code = ((Number) reply.get(0)).longValue();
        int issueNumber = ((Number) reply.get(1)).intValue();
        if (code == RESERVED) {
            return new Result.Reserved(issueNumber);
        }
        if (code == EXISTING) {
            return new Result.Existing(issueNumber);
        }
        if (code == SOLD_OUT) {
            return new Result.SoldOut();
        }
        if (code == LOST) {
            return new Result.Lost();
        }
        if (code == CLOSED) {
            return new Result.Closed();
        }
        throw new IllegalStateException("예약 Lua 가 모르는 값을 돌려줬습니다: " + reply);
    }

    @Override
    public void prepare(Long campaignId) {
        redis.opsForZSet().add(key(campaignId), READY, READY_SCORE);
    }

    @Override
    public void cancel(Long campaignId, Long userId) {
        redis.opsForZSet().remove(key(campaignId), String.valueOf(userId));
    }

    @Override
    public void clear(Long campaignId) {
        redis.unlink(key(campaignId));
    }

    @Override
    public void restore(Long campaignId, List<Reservation> reservations) {
        if (reservations.isEmpty()) {
            return;
        }
        String key = key(campaignId);
        redis.executePipelined((RedisCallback<Object>) connection -> {
            for (Reservation reservation : reservations) {
                connection.zSetCommands().zAdd(key.getBytes(StandardCharsets.UTF_8), reservation.issueNumber(),
                    String.valueOf(reservation.userId()).getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });
    }

    @Override
    public int largestNumber(Long campaignId) {
        Set<ZSetOperations.TypedTuple<String>> top = redis.opsForZSet().reverseRangeWithScores(key(campaignId), 0, 0);
        if (top == null || top.isEmpty()) {
            return 0;
        }
        return top.iterator().next().getScore().intValue();
    }

    @Override
    public long count(Long campaignId) {
        Long size = redis.opsForZSet().zCard(key(campaignId));
        if (size == null || size == 0) {
            return 0;
        }
        return redis.opsForZSet().score(key(campaignId), READY) == null ? size : size - 1;
    }

    @Override
    public List<Reservation> reservedBetween(Long campaignId, int afterNumber, int toNumber, long offset, int limit) {
        Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet()
            .rangeByScoreWithScores(key(campaignId), afterNumber + 1, toNumber, offset, limit);
        if (tuples == null) {
            return List.of();
        }
        return tuples.stream()
            .map(tuple -> new Reservation(Long.valueOf(tuple.getValue()), tuple.getScore().intValue()))
            .toList();
    }
}
