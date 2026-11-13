package com.loopers.infrastructure.coupon;

import com.loopers.application.coupon.admission.AcquireOutcome;
import com.loopers.application.coupon.admission.CouponAdmissionAdmin;
import com.loopers.application.coupon.admission.CouponAdmissionException;
import com.loopers.application.coupon.admission.CouponIssueAdmission;
import com.loopers.application.coupon.admission.ReserveOutcome;
import com.loopers.application.coupon.admission.ReserveResult;
import com.loopers.config.redis.RedisConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 쿠폰 수용의 Redis 구현. 판정·자리·결과 기록은 Lua 한 번으로 원자 실행한다.
 *
 * <p>수용 판단은 replica 가 아니라 master 에서 한다. Redis 접근 실패·timeout·모르는 응답은 모두 {@link CouponAdmissionException} 으로 바꾼다.
 */
@Component
public class CouponIssueRedisAdmission implements CouponIssueAdmission, CouponAdmissionAdmin {

    private static final String ISSUED_PREFIX = "ISSUED:";
    private static final int SCAN_COUNT = 500;

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

    @Override
    public void recordIssued(Long campaignId, Long userId, Long couponId) {
        // 빈 token 을 넘기면 finish 스크립트의 SREM 은 아무것도 지우지 않고 결과만 기록한다.
        run(finishScript, List.of(winnersKey(campaignId), runningKey(campaignId)),
            String.valueOf(userId), "", String.valueOf(couponId));
    }

    @Override
    public void prepare(Long campaignId, int quota) {
        access(() -> {
            redisTemplate.opsForHash().putAll(metaKey(campaignId), Map.of("active", "1", "open", "0", "quota", String.valueOf(quota)));
            return null;
        });
    }

    @Override
    public void suspend(Long campaignId) {
        access(() -> {
            redisTemplate.opsForHash().put(metaKey(campaignId), "open", "0");
            return null;
        });
    }

    // 신규 판정을 먼저 멈춘(suspend) 뒤에만 부른다. 지우고 다시 쓰는 사이에 판정이 끼어들지 않는다.
    @Override
    public void replaceWinners(Long campaignId, Map<Long, String> winners) {
        Map<String, String> values = new HashMap<>();
        winners.forEach((userId, value) -> values.put(String.valueOf(userId), value));
        access(() -> {
            redisTemplate.delete(winnersKey(campaignId));
            if (!values.isEmpty()) {
                redisTemplate.opsForHash().putAll(winnersKey(campaignId), values);
            }
            return null;
        });
    }

    @Override
    public void releaseTokens(Long campaignId, Collection<String> tokens) {
        if (tokens.isEmpty()) {
            return;
        }
        access(() -> redisTemplate.opsForSet().remove(runningKey(campaignId), tokens.toArray()));
    }

    @Override
    public void open(Long campaignId, int quota, boolean active) {
        access(() -> {
            redisTemplate.opsForHash().putAll(metaKey(campaignId),
                Map.of("quota", String.valueOf(quota), "active", active ? "1" : "0", "open", "1"));
            return null;
        });
    }

    @Override
    public void deactivate(Long campaignId) {
        access(() -> {
            redisTemplate.opsForHash().put(metaKey(campaignId), "active", "0");
            return null;
        });
    }

    @Override
    public List<Long> findUnissuedWinners(Long campaignId) {
        return access(() -> {
            List<Long> userIds = new ArrayList<>();
            ScanOptions options = ScanOptions.scanOptions().count(SCAN_COUNT).build();
            try (Cursor<Map.Entry<Object, Object>> cursor = redisTemplate.opsForHash().scan(winnersKey(campaignId), options)) {
                cursor.forEachRemaining(entry -> {
                    String value = entry.getValue().toString();
                    if (value.equals("PENDING") || value.equals("TICKETED")) {
                        userIds.add(Long.valueOf(entry.getKey().toString()));
                    }
                });
            }
            return userIds;
        });
    }

    private <T> T access(Supplier<T> command) {
        try {
            return command.get();
        } catch (DataAccessException e) {
            throw new CouponAdmissionException("쿠폰 수용 상태를 바꾸지 못했습니다.", e);
        }
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
