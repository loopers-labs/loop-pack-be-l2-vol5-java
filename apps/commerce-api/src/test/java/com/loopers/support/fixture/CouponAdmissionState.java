package com.loopers.support.fixture;

import com.loopers.config.redis.RedisConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 테스트에서 캠페인의 Redis 수용 상태(meta·winners·running)를 직접 만들고 읽는다. 정리는 캠페인 키만 지운다.
 * 키 형식은 운영 어댑터와 같아야 하며, 다르면 어댑터 테스트가 실패해 드러난다.
 */
@Component
public class CouponAdmissionState {

    private final RedisTemplate<String, String> redisTemplate;

    public CouponAdmissionState(@Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void meta(long campaignId, boolean open, boolean active, int quota) {
        redisTemplate.opsForHash().putAll(metaKey(campaignId), Map.of(
            "open", open ? "1" : "0",
            "active", active ? "1" : "0",
            "quota", String.valueOf(quota)
        ));
    }

    public void winner(long campaignId, long userId, String value) {
        redisTemplate.opsForHash().put(winnersKey(campaignId), String.valueOf(userId), value);
    }

    public void running(long campaignId, String... tokens) {
        redisTemplate.opsForSet().add(runningKey(campaignId), tokens);
    }

    public String winnerOf(long campaignId, long userId) {
        Object value = redisTemplate.opsForHash().get(winnersKey(campaignId), String.valueOf(userId));
        return value == null ? null : value.toString();
    }

    public long winnerCount(long campaignId) {
        return redisTemplate.opsForHash().size(winnersKey(campaignId));
    }

    public Set<String> runningTokens(long campaignId) {
        return redisTemplate.opsForSet().members(runningKey(campaignId));
    }

    /** 키의 남은 수명(초). 만료가 없으면 -1 이다. */
    public long runningTtlSeconds(long campaignId) {
        return redisTemplate.getExpire(runningKey(campaignId));
    }

    public void clear(long campaignId) {
        redisTemplate.delete(List.of(metaKey(campaignId), winnersKey(campaignId), runningKey(campaignId)));
    }

    private String metaKey(long campaignId) {
        return "coupon-issue:{" + campaignId + "}:meta";
    }

    private String winnersKey(long campaignId) {
        return "coupon-issue:{" + campaignId + "}:winners";
    }

    private String runningKey(long campaignId) {
        return "coupon-issue:{" + campaignId + "}:running";
    }
}
