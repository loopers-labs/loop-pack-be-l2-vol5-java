package com.loopers.infrastructure.coupon.client;

import com.loopers.config.CouponRedisConfig;
import com.loopers.domain.coupon.gateway.AdmissionSlots;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/** 처리 자리 token Set. 캐시 · 접수 키와 섞지 않는 전용 키를 캠페인마다 둔다(D-159). TTL 은 두지 않는다(D-160) */
@Component
public class RedisAdmissionSlots implements AdmissionSlots {

    private static final RedisScript<Long> ACQUIRE = acquireScript();

    private final StringRedisTemplate redis;

    public RedisAdmissionSlots(@Qualifier(CouponRedisConfig.MASTER_TEMPLATE) StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean acquire(long campaignId, int capacity, String token) {
        Long result = redis.execute(ACQUIRE, List.of(key(campaignId)), String.valueOf(capacity), token);
        if (result == null) {
            throw new IllegalStateException("수용 스크립트가 결과를 돌려주지 않음. campaignId=" + campaignId);
        }
        return result == 1L;
    }

    @Override
    public void release(long campaignId, String token) {
        redis.opsForSet().remove(key(campaignId), token);
    }

    @Override
    public Set<String> tokens(long campaignId) {
        Set<String> tokens = redis.opsForSet().members(key(campaignId));
        return tokens == null ? Set.of() : tokens;
    }

    static String key(long campaignId) {
        return "coupon:admission:{" + campaignId + "}";
    }

    private static RedisScript<Long> acquireScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/coupon/admission-acquire.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
