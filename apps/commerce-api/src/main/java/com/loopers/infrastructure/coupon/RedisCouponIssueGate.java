package com.loopers.infrastructure.coupon;

import com.loopers.config.redis.RedisConfig;
import com.loopers.domain.coupon.CouponIssueGate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 캠페인마다 판단 정보(hash: remaining, endAt)와 사용자 상태(hash: 사용자 ID → "A" DB 발급 확인 | "R:UUID" 실행 예약)를 둔다.
 * 판단과 기록이 사이에 끼어들지 않도록 Lua 스크립트 하나로 실행한다. 읽기도 판단의 일부라 master에만 보낸다.
 */
@Component
@ConditionalOnProperty(name = "commerce.coupon.issue-gate.store", havingValue = "REDIS", matchIfMissing = true)
public class RedisCouponIssueGate implements CouponIssueGate {
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final RedisScript<List<String>> ENTER = (RedisScript) script("coupon-gate-enter.lua", List.class);
    private static final RedisScript<Long> CONFIRM = script("coupon-gate-confirm.lua", Long.class);
    private static final RedisScript<Long> CANCEL = script("coupon-gate-cancel.lua", Long.class);
    private static final RedisScript<Long> CANCEL_FINAL = script("coupon-gate-cancel-final.lua", Long.class);
    private static final RedisScript<Long> PREPARE = script("coupon-gate-prepare.lua", Long.class);
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final RedisScript<List<String>> ENQUEUE = (RedisScript) script("coupon-gate-enqueue.lua", List.class);
    /** 작업 큐의 소비자 그룹. */
    public static final String JOB_GROUP = "coupon-issue-workers";

    private final RedisTemplate<String, String> redis;

    public RedisCouponIssueGate(@Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redis) {
        this.redis = redis;
    }

    @Override
    public Entry enter(Long campaignId, Long userId, Instant now) {
        List<String> result = redis.execute(ENTER, keys(campaignId), userId.toString(),
            Long.toString(now.toEpochMilli()), "R:" + UUID.randomUUID());
        if (result == null || result.size() != 2) {
            throw new IllegalStateException("Coupon gate returned no result");
        }
        return new Entry(Decision.valueOf(result.get(0)), result.get(1).isEmpty() ? null : result.get(1));
    }

    @Override
    public Entry enqueue(Long campaignId, Long userId, Instant now) {
        List<String> result = redis.execute(ENQUEUE, List.of(stockKey(campaignId), usersKey(campaignId), jobsKey(campaignId)),
            userId.toString(), Long.toString(now.toEpochMilli()), "R:" + UUID.randomUUID());
        if (result == null || result.size() != 2) {
            throw new IllegalStateException("Coupon gate returned no result");
        }
        return new Entry(Decision.valueOf(result.get(0)), result.get(1).isEmpty() ? null : result.get(1));
    }

    @Override
    public UserState state(Long campaignId, Long userId) {
        Object value = redis.opsForHash().get(usersKey(campaignId), userId.toString());
        if (value == null) return UserState.NONE;
        return "A".equals(value) ? UserState.ACCEPTED : UserState.RESERVED;
    }

    @Override
    public boolean confirm(Long campaignId, Long userId, String reservation, boolean created) {
        return Long.valueOf(1).equals(redis.execute(CONFIRM, keys(campaignId), userId.toString(), reservation, created ? "1" : "0"));
    }

    @Override
    public void cancel(Long campaignId, Long userId, String reservation) {
        redis.execute(CANCEL, keys(campaignId), userId.toString(), reservation);
    }

    @Override
    public void cancelFinally(Long campaignId, Long userId, String reservation) {
        redis.execute(CANCEL_FINAL, keys(campaignId), userId.toString(), reservation);
    }

    @Override
    public void prepare(Long campaignId, int remaining, Collection<Long> issuedUserIds, Instant endAt, Instant expireAt) {
        List<String> arguments = new ArrayList<>(List.of(Integer.toString(remaining),
            Long.toString(endAt.toEpochMilli()), Long.toString(expireAt.toEpochMilli())));
        issuedUserIds.forEach(userId -> arguments.add(userId.toString()));
        redis.execute(PREPARE, keys(campaignId), arguments.toArray());
    }

    // 중괄호 안의 캠페인 ID가 같으면 Redis Cluster에서도 같은 slot에 놓여 한 스크립트로 두 키를 다룰 수 있다.
    public static String stockKey(Long campaignId) {
        return "coupon-issue:{" + campaignId + "}:stock";
    }

    public static String usersKey(Long campaignId) {
        return "coupon-issue:{" + campaignId + "}:users";
    }

    /** 계속 실패해 보류한 작업(hash: 작업 ID → 내용). 수동 확인 대상이다. */
    public static String heldKey(Long campaignId) {
        return "coupon-issue:{" + campaignId + "}:held";
    }

    /** 작업 큐(stream). 판단 키와 같은 slot에 두어 한 Lua에서 같이 쓴다. */
    public static String jobsKey(Long campaignId) {
        return "coupon-issue:{" + campaignId + "}:jobs";
    }

    private static List<String> keys(Long campaignId) {
        return List.of(stockKey(campaignId), usersKey(campaignId));
    }

    private static <T> RedisScript<T> script(String name, Class<T> type) {
        return RedisScript.of(new ClassPathResource("redis/" + name), type);
    }
}
