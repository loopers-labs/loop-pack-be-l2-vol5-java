package com.loopers.infrastructure.coupon;

import com.loopers.application.coupon.admission.AcquireOutcome;
import com.loopers.application.coupon.admission.CouponAdmissionException;
import com.loopers.application.coupon.admission.CouponIssueAdmission;
import com.loopers.application.coupon.admission.ReserveOutcome;
import com.loopers.application.coupon.admission.ReserveResult;
import com.loopers.config.redis.RedisConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 쿠폰 수용의 Redis 구현. 판정·자리·결과 기록은 Lua 한 번으로 원자 실행한다.
 *
 * <p>수용 판단은 replica 가 아니라 master 에서 한다. Redis 접근 실패·timeout·모르는 응답은 모두 {@link CouponAdmissionException} 으로 바꾼다.
 */
@Component
public class CouponIssueRedisAdmission implements CouponIssueAdmission {

    private static final String ISSUED_PREFIX = "ISSUED:";

    private final RedisTemplate<String, String> redisTemplate;
    private final int capacity;
    private final DefaultRedisScript<String> reserveScript = script("redis/coupon-reserve.lua");
    private final DefaultRedisScript<String> acquireScript = script("redis/coupon-acquire.lua");
    private final DefaultRedisScript<String> ticketScript = script("redis/coupon-ticket.lua");
    private final DefaultRedisScript<String> finishScript = script("redis/coupon-finish.lua");

    public CouponIssueRedisAdmission(
        @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redisTemplate,
        @Value("${coupon.issue.capacity}") int capacity
    ) {
        this.redisTemplate = redisTemplate;
        this.capacity = capacity;
    }

    @Override
    public ReserveResult reserve(Long campaignId, Long userId, String token) {
        String reply = run(reserveScript, List.of(metaKey(campaignId), winnersKey(campaignId), runningKey(campaignId)),
            String.valueOf(userId), token, String.valueOf(capacity));
        if (reply.startsWith(ISSUED_PREFIX)) {
            return ReserveResult.issued(parseCouponId(reply.substring(ISSUED_PREFIX.length())));
        }
        return ReserveResult.of(parse(ReserveOutcome.class, reply));
    }

    @Override
    public AcquireOutcome acquire(Long campaignId, Long userId, String token) {
        String reply = run(acquireScript, List.of(metaKey(campaignId), winnersKey(campaignId), runningKey(campaignId)),
            String.valueOf(userId), token, String.valueOf(capacity));
        return parse(AcquireOutcome.class, reply);
    }

    @Override
    public void markTicketed(Long campaignId, Long userId) {
        run(ticketScript, List.of(winnersKey(campaignId)), String.valueOf(userId));
    }

    @Override
    public void finish(Long campaignId, Long userId, String token, Long couponId) {
        run(finishScript, List.of(winnersKey(campaignId), runningKey(campaignId)),
            String.valueOf(userId), token, couponId == null ? "" : String.valueOf(couponId));
    }

    private String run(DefaultRedisScript<String> script, List<String> keys, String... args) {
        String reply;
        try {
            reply = redisTemplate.execute(script, keys, (Object[]) args);
        } catch (DataAccessException e) {
            throw new CouponAdmissionException("쿠폰 수용 상태를 확인하지 못했습니다.", e);
        }
        if (reply == null) {
            throw new CouponAdmissionException("쿠폰 수용 스크립트가 응답을 돌려주지 않았습니다.");
        }
        return reply;
    }

    private <E extends Enum<E>> E parse(Class<E> type, String reply) {
        try {
            return Enum.valueOf(type, reply);
        } catch (IllegalArgumentException e) {
            throw new CouponAdmissionException("알 수 없는 쿠폰 수용 응답입니다: " + reply, e);
        }
    }

    private Long parseCouponId(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            throw new CouponAdmissionException("알 수 없는 쿠폰 ID 입니다: " + value, e);
        }
    }

    private static DefaultRedisScript<String> script(String path) {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(String.class);
        return script;
    }

    // {campaignId} 는 hash tag 다. 한 캠페인의 키를 같은 슬롯에 두어 스크립트가 함께 다룰 수 있게 한다.
    private static String metaKey(Long campaignId) {
        return "coupon-issue:{" + campaignId + "}:meta";
    }

    private static String winnersKey(Long campaignId) {
        return "coupon-issue:{" + campaignId + "}:winners";
    }

    private static String runningKey(Long campaignId) {
        return "coupon-issue:{" + campaignId + "}:running";
    }
}
