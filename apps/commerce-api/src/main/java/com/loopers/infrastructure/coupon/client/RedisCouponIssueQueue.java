package com.loopers.infrastructure.coupon.client;

import com.loopers.config.CouponRedisConfig;
import com.loopers.domain.coupon.enums.CouponErrorCode;
import com.loopers.domain.coupon.enums.IssueState;
import com.loopers.domain.coupon.gateway.CouponIssueQueue;
import com.loopers.domain.coupon.vo.IssueAcceptance;
import com.loopers.domain.coupon.vo.IssueSnapshot;
import com.loopers.support.error.CoreException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class RedisCouponIssueQueue implements CouponIssueQueue {

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> ACCEPT = script("redis/coupon/accept.lua");
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> CONFIRM = script("redis/coupon/confirm.lua");
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> READ = script("redis/coupon/read.lua");

    private final StringRedisTemplate redis;

    public RedisCouponIssueQueue(@Qualifier(CouponRedisConfig.MASTER_TEMPLATE) StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public IssueAcceptance accept(long campaignId, long userId, int totalQuota, int reserveLimit, long nowMillis) {
        List<?> result = execute(ACCEPT, CouponIssueKeys.of(campaignId).forAccept(),
            String.valueOf(userId), String.valueOf(reserveLimit), String.valueOf(nowMillis));
        return switch (tag(result)) {
            case "PENDING" -> new IssueAcceptance.Pending(Long.parseLong(text(result, 1)));
            case "EXISTING" -> new IssueAcceptance.Existing(snapshot(result, totalQuota));
            case "SOLD_OUT" -> new IssueAcceptance.SoldOut();
            default -> throw unexpected(result);
        };
    }

    @Override
    public Optional<IssueSnapshot> confirm(long campaignId, long userId, int totalQuota) {
        List<?> result = execute(CONFIRM, CouponIssueKeys.of(campaignId).forConfirm(),
            String.valueOf(userId), String.valueOf(totalQuota));
        return stateOrEmpty(result, totalQuota);
    }

    @Override
    public Optional<IssueSnapshot> read(long campaignId, long userId, int totalQuota) {
        List<?> result = execute(READ, CouponIssueKeys.of(campaignId).forRead(), String.valueOf(userId));
        return stateOrEmpty(result, totalQuota);
    }

    @SuppressWarnings("rawtypes")
    private List<?> execute(RedisScript<List> script, List<String> keys, String... args) {
        List<?> result = redis.execute(script, keys, (Object[]) args);
        if (result == null || result.isEmpty()) {
            throw new IllegalStateException("쿠폰 접수 스크립트가 결과를 돌려주지 않음. keys=" + keys);
        }
        if ("CLOSED".equals(tag(result))) {
            throw new CoreException(CouponErrorCode.COUPON_ISSUE_UNAVAILABLE);
        }
        return result;
    }

    private static Optional<IssueSnapshot> stateOrEmpty(List<?> result, int totalQuota) {
        return switch (tag(result)) {
            case "STATE" -> Optional.of(snapshot(result, totalQuota));
            case "NONE" -> Optional.empty();
            default -> throw unexpected(result);
        };
    }

    /** 저장값은 "상태:순번" 이고 ISSUED 는 "ISSUED:순번:couponId" */
    private static IssueSnapshot snapshot(List<?> result, int totalQuota) {
        String[] stored = text(result, 1).split(":");
        IssueState state = IssueState.valueOf(stored[0]);
        long sequence = Long.parseLong(stored[1]);
        Long couponId = stored.length > 2 ? Long.valueOf(stored[2]) : null;
        boolean soldOut = Long.parseLong(text(result, 2)) == 1L;
        long promoted = Long.parseLong(text(result, 3));
        return IssueSnapshot.of(state, sequence, couponId, soldOut, promoted, totalQuota);
    }

    private static String tag(List<?> result) {
        return text(result, 0);
    }

    private static String text(List<?> result, int index) {
        return String.valueOf(result.get(index));
    }

    private static IllegalStateException unexpected(List<?> result) {
        return new IllegalStateException("쿠폰 접수 스크립트의 결과를 해석할 수 없음. result=" + result);
    }

    @SuppressWarnings("rawtypes")
    private static RedisScript<List> script(String path) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(List.class);
        return script;
    }
}
