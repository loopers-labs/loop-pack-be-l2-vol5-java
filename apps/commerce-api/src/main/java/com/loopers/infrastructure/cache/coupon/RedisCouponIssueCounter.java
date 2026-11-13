package com.loopers.infrastructure.cache.coupon;

import com.loopers.application.coupon.port.CouponCounterRebuildStore;
import com.loopers.application.coupon.support.CouponCounterException;
import com.loopers.domain.coupon.repository.CouponIssueCounter;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

@Slf4j
@Repository
public class RedisCouponIssueCounter implements CouponIssueCounter, CouponCounterRebuildStore {
    private static final DefaultRedisScript<Long> RECORD = script("record");
    private static final DefaultRedisScript<Long> FIND = script("find");
    private static final DefaultRedisScript<Long> STAGE = script("stage");
    private static final DefaultRedisScript<Long> APPEND = script("append-stage");
    private static final DefaultRedisScript<Long> PUBLISH = script("publish");
    private final RedisTemplate<String, String> redis;

    public RedisCouponIssueCounter(@Qualifier("couponCounterRedisTemplate") RedisTemplate<String, String> redis) {
        this.redis = redis;
    }

    @Override
    public boolean recordIssued(long campaignId, long couponId) {
        positive(couponId);
        Long result = redis.execute(RECORD, List.of(activeKey(campaignId), fenceKey(campaignId)),
            Long.toString(couponId), UUID.randomUUID().toString());
        if (result == null || (result != 0 && result != 1)) {
            throw new CouponCounterException("Coupon aggregate is unprepared or record outcome is unknown");
        }
        return result == 1;
    }

    @Override
    public OptionalLong findIssuedCount(long campaignId) {
        Long result = redis.execute(FIND, List.of(activeKey(campaignId)));
        if (result == null || result < -1) {
            throw new CouponCounterException("Coupon aggregate read outcome is unknown");
        }
        return result == -1 ? OptionalLong.empty() : OptionalLong.of(result);
    }

    @Override
    public void replace(long campaignId, List<Long> committedCouponIds) {
        String active = activeKey(campaignId);
        committedCouponIds.forEach(RedisCouponIssueCounter::positive);
        String token = UUID.randomUUID().toString();
        String stage = "coupon:counter:{" + campaignId + "}:build:" + token;
        // fence는 active와 별도로 보존한다. timeout 뒤 지연된 publish가 최신 결과를 덮어쓰지 못한다.
        try {
            requireOne(redis.execute(STAGE, List.of(stage, fenceKey(campaignId)), token));
            for (int offset = 0; offset < committedCouponIds.size(); offset += 500) {
                Object[] batch = committedCouponIds.subList(offset, Math.min(offset + 500, committedCouponIds.size()))
                    .stream().map(String::valueOf).toArray();
                requireOne(redis.execute(APPEND, List.of(stage), batch));
            }
            requireOne(redis.execute(PUBLISH, List.of(stage, active, fenceKey(campaignId)),
                Integer.toString(committedCouponIds.size()), token));
        } finally {
            try {
                redis.delete(stage);
            } catch (RuntimeException cleanupFailure) {
                log.warn("coupon campaign={} stage=COUNTER_BUILD_CLEANUP_FAILED key={}",
                    campaignId, stage, cleanupFailure);
            }
        }
    }

    public static String activeKey(long campaignId) {
        positive(campaignId);
        return "coupon:counter:{" + campaignId + "}:active";
    }

    private static String fenceKey(long campaignId) {
        return "coupon:counter:{" + campaignId + "}:fence";
    }

    private static void positive(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("Coupon aggregate IDs must be positive");
        }
    }

    private static void requireOne(Long result) {
        if (result == null || result != 1) {
            throw new CouponCounterException("Coupon aggregate build is incomplete or publish outcome is unknown");
        }
    }

    private static DefaultRedisScript<Long> script(String action) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/coupon-counter-" + action + ".lua"));
        script.setResultType(Long.class);
        return script;
    }
}
