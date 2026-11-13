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

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class RedisCouponIssueStream implements CouponIssueStream {

    static final String GROUP = "coupon-issue-worker";

    private static final RedisScript<Long> ISSUED = script("redis/coupon/issued.lua", Long.class);
    private static final RedisScript<Long> DELAY = script("redis/coupon/delay.lua", Long.class);
    private static final RedisScript<Long> MOVE = script("redis/coupon/move.lua", Long.class);
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> CLAIM = script("redis/coupon/claim.lua", List.class);
    private static final RedisScript<String> FAIL = script("redis/coupon/fail.lua", String.class);

    private final StringRedisTemplate redis;

    public RedisCouponIssueStream(@Qualifier(CouponRedisConfig.MASTER_TEMPLATE) StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public int moveDue(long campaignId, long nowMillis, int limit) {
        CouponIssueKeys keys = CouponIssueKeys.of(campaignId);
        Long moved = redis.execute(MOVE, List.of(keys.delayed(), keys.stream()), String.valueOf(nowMillis), String.valueOf(limit));
        return moved == null ? 0 : moved.intValue();
    }

    @Override
    public List<IssueEntry> claim(long campaignId, String consumer, Duration minIdle, int count) {
        CouponIssueKeys keys = CouponIssueKeys.of(campaignId);
        if (!ready(keys)) {
            return List.of();
        }
        ensureGroup(keys);
        List<?> claimed = redis.execute(CLAIM, List.of(keys.stream()),
            GROUP, consumer, String.valueOf(minIdle.toMillis()), String.valueOf(count));
        if (claimed == null) {
            return List.of();
        }
        List<IssueEntry> entries = new ArrayList<>(claimed.size());
        for (Object item : claimed) {
            List<?> parts = (List<?>) item;
            entries.add(entry(campaignId, String.valueOf(parts.get(0)), fields((List<?>) parts.get(1)),
                Long.parseLong(String.valueOf(parts.get(2)))));
        }
        return entries;
    }

    @Override
    public List<IssueEntry> read(long campaignId, String consumer, int count) {
        CouponIssueKeys keys = CouponIssueKeys.of(campaignId);
        if (!ready(keys)) {
            return List.of();
        }
        ensureGroup(keys);
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
            Consumer.from(GROUP, consumer),
            StreamReadOptions.empty().count(count),
            StreamOffset.create(keys.stream(), ReadOffset.lastConsumed()));
        if (records == null) {
            return List.of();
        }
        return records.stream()
            .map(record -> entry(campaignId, record.getId().getValue(), record.getValue(), 1L))
            .toList();
    }

    @Override
    public void completeIssued(IssueEntry entry, long couponId) {
        CouponIssueKeys keys = CouponIssueKeys.of(entry.campaignId());
        redis.execute(ISSUED, List.of(keys.users(), keys.stream()),
            String.valueOf(entry.userId()), String.valueOf(entry.sequence()), String.valueOf(couponId), GROUP, entry.entryId());
    }

    @Override
    public void delay(IssueEntry entry, long nextAtMillis) {
        CouponIssueKeys keys = CouponIssueKeys.of(entry.campaignId());
        String member = String.join("|", String.valueOf(entry.userId()), String.valueOf(entry.sequence()),
            String.valueOf(entry.slotNo()), entry.epoch(), String.valueOf(entry.unclassified()), String.valueOf(entry.transientAttempts()));
        redis.execute(DELAY, List.of(keys.stream(), keys.delayed()), GROUP, entry.entryId(), String.valueOf(nextAtMillis), member);
    }

    @Override
    public Long fail(IssueEntry entry, boolean promote) {
        CouponIssueKeys keys = CouponIssueKeys.of(entry.campaignId());
        String promoted = redis.execute(FAIL, keys.forFail(), String.valueOf(entry.userId()), GROUP, entry.entryId(),
            String.valueOf(entry.slotNo()), promote ? "1" : "0", entry.epoch());
        return promoted == null || promoted.isEmpty() ? null : Long.valueOf(promoted);
    }

    @Override
    public void markSoldOut(long campaignId) {
        redis.opsForValue().set(CouponIssueKeys.of(campaignId).soldOut(), "1");
    }

    /** 표식이 없으면 상태를 잃은 것이라 처리하지 않는다 (D-139) */
    private boolean ready(CouponIssueKeys keys) {
        return Boolean.TRUE.equals(redis.hasKey(keys.epoch())) && Boolean.TRUE.equals(redis.hasKey(keys.stream()));
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

    private static Map<Object, Object> fields(List<?> flat) {
        Map<Object, Object> fields = new HashMap<>();
        for (int i = 0; i + 1 < flat.size(); i += 2) {
            fields.put(String.valueOf(flat.get(i)), flat.get(i + 1));
        }
        return fields;
    }

    private static IssueEntry entry(long campaignId, String entryId, Map<Object, Object> value, long deliveries) {
        return new IssueEntry(
            campaignId,
            entryId,
            Long.parseLong(String.valueOf(value.get("userId"))),
            Long.parseLong(String.valueOf(value.get("sequence"))),
            Integer.parseInt(String.valueOf(value.get("slotNo"))),
            String.valueOf(value.get("epoch")),
            intOrZero(value.get("unclassified")),
            intOrZero(value.get("transient")),
            deliveries);
    }

    private static int intOrZero(Object value) {
        return value == null ? 0 : Integer.parseInt(String.valueOf(value));
    }

    private static <T> RedisScript<T> script(String path, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(resultType);
        return script;
    }
}
