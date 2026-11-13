package com.loopers.infrastructure.coupon;

import com.loopers.config.redis.RedisConfig;
import com.loopers.domain.coupon.CouponReservation;
import com.loopers.domain.coupon.IssueQuota;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
class RedisCouponReservation implements CouponReservation {

    private static final long RESERVED = 1L;
    private static final long EXISTING = 0L;
    private static final long SOLD_OUT = -1L;
    private static final long LOST = -2L;

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> RESERVE =
        RedisScript.of(new ClassPathResource("redis/coupon-reservation-reserve.lua"), List.class);

    private final RedisTemplate<String, String> redis;

    RedisCouponReservation(@Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redis) {
        this.redis = redis;
    }

    static String key(Long campaignId) {
        return "coupon:campaign:" + campaignId + ":issued";
    }

    @Override
    public Result reserve(Long campaignId, Long userId, IssueQuota quota, int dbIssuedCount) {
        List<?> reply = redis.execute(RESERVE, List.of(key(campaignId)),
            String.valueOf(userId), String.valueOf(quota.value()), String.valueOf(dbIssuedCount));
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
        throw new IllegalStateException("예약 Lua 가 모르는 값을 돌려줬습니다: " + reply);
    }

    @Override
    public void cancel(Long campaignId, Long userId) {
        redis.opsForZSet().remove(key(campaignId), String.valueOf(userId));
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
        return size == null ? 0 : size;
    }

    @Override
    public List<Reservation> reservedBetween(Long campaignId, int afterNumber, int toNumber, int limit) {
        Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet()
            .rangeByScoreWithScores(key(campaignId), afterNumber + 1, toNumber, 0, limit);
        if (tuples == null) {
            return List.of();
        }
        return tuples.stream()
            .map(tuple -> new Reservation(Long.valueOf(tuple.getValue()), tuple.getScore().intValue()))
            .toList();
    }
}
