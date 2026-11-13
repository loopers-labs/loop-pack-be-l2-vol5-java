package com.loopers.infrastructure.coupon.client;

import com.loopers.config.CouponRedisConfig;
import com.loopers.domain.coupon.gateway.CouponIssueStream;
import com.loopers.domain.coupon.vo.IssueEntry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class RedisCouponIssueStream implements CouponIssueStream {

    static final String GROUP = "coupon-issue-worker";

    private static final RedisScript<Long> ISSUED = script("redis/coupon/issued.lua");

    private final StringRedisTemplate redis;

    public RedisCouponIssueStream(@Qualifier(CouponRedisConfig.MASTER_TEMPLATE) StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public List<IssueEntry> read(long campaignId, String consumer, int count) {
        CouponIssueKeys keys = CouponIssueKeys.of(campaignId);
        // 표식이 없으면 상태를 잃은 것이라 처리하지 않는다 (D-139)
        if (!Boolean.TRUE.equals(redis.hasKey(keys.epoch())) || !Boolean.TRUE.equals(redis.hasKey(keys.stream()))) {
            return List.of();
        }
        ensureGroup(keys);
        @SuppressWarnings("unchecked")
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
            Consumer.from(GROUP, consumer),
            StreamReadOptions.empty().count(count),
            StreamOffset.create(keys.stream(), ReadOffset.lastConsumed()));
        if (records == null) {
            return List.of();
        }
        return records.stream().map(record -> entry(campaignId, record)).toList();
    }

    @Override
    public void completeIssued(IssueEntry entry, long couponId) {
        CouponIssueKeys keys = CouponIssueKeys.of(entry.campaignId());
        redis.execute(ISSUED, List.of(keys.users(), keys.stream()),
            String.valueOf(entry.userId()), String.valueOf(entry.sequence()), String.valueOf(couponId), GROUP, entry.entryId());
    }

    /**
     * 읽기 전에 group 을 만든다(이미 있으면 그대로). 0 부터라 group 을 만들기 전에 쌓인 항목도 받는다.
     * 만든 사실을 기억하지 않는다. Redis 재구성으로 group 이 사라져도 다시 만든다 (D-140)
     */
    private void ensureGroup(CouponIssueKeys keys) {
        try {
            redis.opsForStream().createGroup(keys.stream(), ReadOffset.from("0"), GROUP);
        } catch (RedisSystemException e) {
            if (!String.valueOf(e.getMostSpecificCause().getMessage()).contains("BUSYGROUP")) {
                throw e;
            }
        }
    }

    private static RedisScript<Long> script(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }

    private static IssueEntry entry(long campaignId, MapRecord<String, Object, Object> record) {
        Map<Object, Object> value = record.getValue();
        return new IssueEntry(
            campaignId,
            record.getId().getValue(),
            Long.parseLong(String.valueOf(value.get("userId"))),
            Long.parseLong(String.valueOf(value.get("sequence"))),
            Integer.parseInt(String.valueOf(value.get("slotNo"))),
            String.valueOf(value.get("epoch")));
    }
}
