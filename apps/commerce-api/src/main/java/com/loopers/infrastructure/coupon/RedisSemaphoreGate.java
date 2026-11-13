package com.loopers.infrastructure.coupon;

import com.loopers.application.coupon.CouponAdmissionProperties;
import com.loopers.application.coupon.SemaphoreGate;
import com.loopers.config.redis.RedisConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis Set 세마포어. 수용 판단은 전용 master 연결로 하고(기본 REPLICA_PREFERRED 연결의 값을 쓰지 않는다),
 * 획득은 Lua 한 번(SISMEMBER·SCARD·SADD)으로 원자적으로 한다. 키와 token은 String 직렬화다.
 *
 * <p>token 관리 규칙: 자리는 TTL이나 시간 경과로 회수하지 않고, 자기 token만 SREM으로 반환한다.
 * 반환에 실패했거나 획득 응답을 받지 못한 token은 {@link #unreleasedTokens}에 기록해 두었다가 {@link #recoverOwnTokens()}로 정리한다.
 * 이전 프로세스가 남긴 고아 token은 {@code instanceId:} 접두어로 자기 것만 찾아 정리한다(단일 인스턴스 전제).</p>
 */
@Slf4j
@Component
public class RedisSemaphoreGate implements SemaphoreGate {

    /** 활성 실행 token의 Set. 캐시 키와 섞지 않는다. */
    static final String PERMITS_KEY = "admission:coupon-issue:permits";

    private static final int SCAN_COUNT = 100;

    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = acquireScript();

    private final RedisTemplate<String, String> redisTemplate;
    private final CouponAdmissionProperties properties;

    /**
     * 이 프로세스에서 자리를 얻으려 하거나 얻어 쓰는 중인 token. 고아 정리가 진행 중인 실행의 자리를 지우지 않도록,
     * Redis를 호출하기 <b>전에</b> 넣고 반환이 끝나거나 획득하지 못했을 때 뺀다.
     */
    private final Set<String> liveTokens = ConcurrentHashMap.newKeySet();

    /** Redis에 남아 있을 수 있는 이 프로세스의 끝난 실행 token(반환 실패, 획득 응답 불확실). 시간이 지났다고 지우지 않는다. */
    private final Set<String> unreleasedTokens = ConcurrentHashMap.newKeySet();

    /** 이전 프로세스가 남긴 자기 인스턴스 token을 한 번 정리했는지. Redis가 안 되면 성공할 때까지 다음 회차에 다시 한다. */
    private volatile boolean previousRunSwept;

    public RedisSemaphoreGate(
        @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redisTemplate,
        CouponAdmissionProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public Admission acquire() {
        String token = properties.instanceId() + ":" + UUID.randomUUID();
        return acquire(token);
    }

    Admission acquire(String token) {
        int effectiveCapacity = effectiveCapacity();
        if (effectiveCapacity < 1) {
            // 유효 용량이 없으면 Redis를 호출하지 않고 자리 없음으로 처리한다.
            return new Admission(Status.FULL, token);
        }
        liveTokens.add(token);
        try {
            Long result = redisTemplate.execute(ACQUIRE_SCRIPT, List.of(PERMITS_KEY), String.valueOf(effectiveCapacity), token);
            if (result != null && result == 1L) {
                return new Admission(Status.ACQUIRED, token);
            }
            if (result != null && result == 0L) {
                liveTokens.remove(token);
                return new Admission(Status.FULL, token);
            }
            return uncertain(token, null);
        } catch (RuntimeException e) {
            return uncertain(token, e);
        }
    }

    @Override
    public void release(Admission admission) {
        if (admission.status() == Status.FULL) {
            return;
        }
        String token = admission.token();
        try {
            redisTemplate.opsForSet().remove(PERMITS_KEY, token);
            unreleasedTokens.remove(token);
        } catch (RuntimeException e) {
            // 쿠폰과 발급 수는 되돌리지 않는다. 자기 token만 기록해 두고 복구 뒤 정리한다.
            unreleasedTokens.add(token);
            log.warn("자리 반환 실패, 복구 뒤 정리할 token으로 기록한다: token={}, error={}", token, e.getMessage());
        } finally {
            // 실행은 끝났다. 남아 있을 수 있는 token은 unreleasedTokens가 이어서 책임진다.
            liveTokens.remove(token);
        }
    }

    /**
     * 자기 token만 정리한다. Redis가 안 되면 예외 없이 멈추고 다음 회차에 다시 한다.
     * <ol>
     *   <li>기록해 둔 미반환 token을 SREM한다</li>
     *   <li>기동 뒤 한 번, Set에서 {@code instanceId:} 접두어 token 중 이 프로세스가 진행 중이지 않은 것을 SREM한다(이전 프로세스의 고아)</li>
     * </ol>
     * 다른 인스턴스의 token과 진행 중인 자기 실행의 token, Set 전체는 건드리지 않는다.
     */
    @Override
    public void recoverOwnTokens() {
        try {
            for (String token : Set.copyOf(unreleasedTokens)) {
                redisTemplate.opsForSet().remove(PERMITS_KEY, token);
                unreleasedTokens.remove(token);
            }
            if (!previousRunSwept) {
                sweepPreviousRunTokens();
                previousRunSwept = true;
            }
        } catch (RuntimeException e) {
            log.warn("고아 token 정리를 다음 회차로 미룬다: {}", e.getMessage());
        }
    }

    private void sweepPreviousRunTokens() {
        String prefix = properties.instanceId() + ":";
        List<String> orphans = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions().match(prefix + "*").count(SCAN_COUNT).build();
        try (Cursor<String> cursor = redisTemplate.opsForSet().scan(PERMITS_KEY, options)) {
            while (cursor.hasNext()) {
                String token = cursor.next();
                if (token.startsWith(prefix) && !liveTokens.contains(token)) {
                    orphans.add(token);
                }
            }
        }
        for (String token : orphans) {
            redisTemplate.opsForSet().remove(PERMITS_KEY, token);
        }
        if (!orphans.isEmpty()) {
            log.warn("이전 실행이 남긴 자기 인스턴스 token {}개를 정리했다: instanceId={}", orphans.size(), properties.instanceId());
        }
    }

    /** 현재 활성 token 수(점유). 읽지 못하면 0이 아니라 예외를 그대로 올린다. */
    public long activeCount() {
        Long size = redisTemplate.opsForSet().size(PERMITS_KEY);
        if (size == null) {
            throw new IllegalStateException("활성 token 수를 읽지 못했다");
        }
        return size;
    }

    /** 반환하지 못해 기록해 둔 이 인스턴스의 token. */
    public Set<String> unreleasedTokens() {
        return Set.copyOf(unreleasedTokens);
    }

    private static DefaultRedisScript<Long> acquireScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/coupon-admission-acquire.lua"));
        script.setResultType(Long.class);
        return script;
    }

    private int effectiveCapacity() {
        return properties.capacity();
    }

    private Admission uncertain(String token, RuntimeException cause) {
        // 응답을 못 받았어도 token이 Set에 들어갔을 수 있다. 이 실행은 직행으로 가므로 진행 중 목록에서 빼고 미반환으로 기록한다.
        liveTokens.remove(token);
        unreleasedTokens.add(token);
        log.warn("세마포어 획득 결과를 알 수 없다: token={}, error={}", token, cause == null ? "null result" : cause.getMessage());
        return new Admission(Status.UNCERTAIN, token);
    }
}
