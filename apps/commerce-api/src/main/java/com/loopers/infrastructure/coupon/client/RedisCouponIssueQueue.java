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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

@Component
public class RedisCouponIssueQueue implements CouponIssueQueue {

    private static final RedisScript<String> OPEN = new DefaultRedisScript<>(
        resource("redis/coupon/open.lua"), String.class);
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
    public void open(long campaignId, int totalQuota, int reserveLimit, String epoch) {
        CouponIssueKeys keys = CouponIssueKeys.of(campaignId);
        redis.execute(OPEN, List.of(keys.epoch(), keys.meta()),
            String.valueOf(totalQuota), String.valueOf(reserveLimit), epoch);
    }

    @Override
    public void close(long campaignId) {
        CouponIssueKeys keys = CouponIssueKeys.of(campaignId);
        redis.opsForHash().put(keys.meta(), "status", "CLOSED");
        redis.opsForValue().set(keys.soldOut(), "1");
    }

    @Override
    public IssueAcceptance accept(long campaignId, long userId, long nowMillis) {
        List<?> result = execute(ACCEPT, CouponIssueKeys.of(campaignId).forAccept(),
            String.valueOf(userId), String.valueOf(nowMillis));
        return switch (tag(result)) {
            case "PENDING" -> new IssueAcceptance.Pending(Long.parseLong(text(result, 1)));
            case "EXISTING" -> new IssueAcceptance.Existing(snapshot(result));
            case "NOT_ACTIVE" -> new IssueAcceptance.NotActive();
            case "SOLD_OUT" -> new IssueAcceptance.SoldOut();
            default -> throw unexpected(result);
        };
    }

    @Override
    public Optional<IssueSnapshot> confirm(long campaignId, long userId) {
        List<?> result = execute(CONFIRM, CouponIssueKeys.of(campaignId).forConfirm(), String.valueOf(userId));
        return stateOrEmpty(result);
    }

    @Override
    public Optional<IssueSnapshot> read(long campaignId, long userId) {
        List<?> result = execute(READ, CouponIssueKeys.of(campaignId).forRead(), String.valueOf(userId));
        return stateOrEmpty(result);
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

    private static Optional<IssueSnapshot> stateOrEmpty(List<?> result) {
        return switch (tag(result)) {
            case "STATE" -> Optional.of(snapshot(result));
            case "NONE" -> Optional.empty();
            default -> throw unexpected(result);
        };
    }

    /** 저장값은 "상태:순번" 이고 ISSUED 는 "ISSUED:순번:couponId". 뒤이어 품절 · 승격 인원 · 한도가 온다 */
    private static IssueSnapshot snapshot(List<?> result) {
        String[] stored = text(result, 1).split(":");
        IssueState state = IssueState.valueOf(stored[0]);
        long sequence = Long.parseLong(stored[1]);
        Long couponId = stored.length > 2 ? Long.valueOf(stored[2]) : null;
        boolean soldOut = Long.parseLong(text(result, 2)) == 1L;
        long promoted = Long.parseLong(text(result, 3));
        int totalQuota = Integer.parseInt(text(result, 4));
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

    private static String resource(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("스크립트를 읽지 못함. path=" + path, e);
        }
    }

    @SuppressWarnings("rawtypes")
    private static RedisScript<List> script(String path) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(List.class);
        return script;
    }
}
