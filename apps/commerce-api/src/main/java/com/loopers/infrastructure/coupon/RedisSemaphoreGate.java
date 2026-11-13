package com.loopers.infrastructure.coupon;

import com.loopers.application.coupon.CouponAdmissionProperties;
import com.loopers.application.coupon.LocalLimiter;
import com.loopers.application.coupon.SemaphoreGate;
import com.loopers.config.redis.RedisConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Redis Set 세마포어. 수용 판단은 전용 master 연결로 하고(기본 REPLICA_PREFERRED 연결의 값을 쓰지 않는다),
 * 획득은 Lua 한 번(SISMEMBER·SCARD·SADD)으로 원자적으로 한다. 키와 token은 String 직렬화다.
 *
 * <p><b>token 관리</b>: 자리는 TTL이나 시간 경과로 회수하지 않고, 자기 token만 SREM으로 반환한다.
 * 반환에 실패했거나 획득 응답을 받지 못한 token은 {@link #unreleasedTokens}에 기록해 두었다가 {@link #recoverOwnTokens()}로 정리한다.
 * 이전 프로세스가 남긴 고아 token은 {@code instanceId:} 접두어로 자기 것만 찾아 정리한다(단일 인스턴스 전제).</p>
 *
 * <p><b>장애와 재개</b>(설계서 8절): 모든 Redis 호출은 CircuitBreaker를 거친다(코드로 조립, AOP 없음).
 * breaker가 열리거나 기동 때 Redis 확인이 실패하면 DEGRADED가 되어 Redis를 호출하지 않고 {@link Status#SKIPPED}를 돌려준다.
 * DEGRADED에서는 최소 대기 뒤 {@link #tryResume()}이 안전 조건을 확인하고 돌아온다.</p>
 * <ol>
 *   <li>쓰기·읽기 왕복을 확인한다(PONG만 보지 않는다). sentinel 키가 없으면 Redis 상태가 유실된 것으로 본다</li>
 *   <li>자기 token을 정리한다(미반환 token SREM, 고아 정리). 남의 token과 Set 전체는 건드리지 않는다</li>
 *   <li>용량을 차감한다: 진행 중인데 Set에 token이 없는 실행과 직행으로 진행 중인 실행은 그것이 끝날 때까지 C에서 뺀다</li>
 *   <li>복귀 직후에는 breaker의 시험(half-open) 호출 수만큼만 Redis를 쓰고, 모두 성공하면 닫는다. 실패하면 다시 DEGRADED가 된다</li>
 * </ol>
 * <p>단일 인스턴스 전제다. 여러 인스턴스이면 재개 직후 짧게 C를 넘을 수 있고, 감지하지 못한 상태 유실까지 안전한 분산 세마포어를 주장하지 않는다.</p>
 */
@Slf4j
@Component
public class RedisSemaphoreGate implements SemaphoreGate {

    /** 활성 실행 token의 Set. 캐시 키와 섞지 않는다. */
    static final String PERMITS_KEY = "admission:coupon-issue:permits";

    /** Redis 상태가 유지되고 있는지 알아보는 표지. 복구 뒤 이 키가 없으면 Redis 상태가 유실된 것으로 본다. */
    static final String EPOCH_KEY = "admission:coupon-issue:epoch";

    /** 재개 전 쓰기·읽기 왕복을 확인하는 키. */
    static final String PROBE_KEY = "admission:coupon-issue:probe";

    private static final Duration PROBE_TTL = Duration.ofSeconds(10);
    private static final int SCAN_COUNT = 100;

    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = acquireScript();

    private final RedisTemplate<String, String> redisTemplate;
    private final CouponAdmissionProperties properties;
    private final LocalLimiter localLimiter;
    private final CircuitBreaker breaker;
    private final ReentrantLock resumeLock = new ReentrantLock();

    /**
     * 이 프로세스에서 자리를 얻으려 하거나 얻어 쓰는 중인 token. 고아 정리가 진행 중인 실행의 자리를 지우지 않도록,
     * Redis를 호출하기 <b>전에</b> 넣고 반환이 끝나거나 획득하지 못했을 때 뺀다.
     */
    private final Set<String> liveTokens = ConcurrentHashMap.newKeySet();

    /** Redis에 남아 있을 수 있는 이 프로세스의 끝난 실행 token(반환 실패, 획득 응답 불확실, 건너뛴 반환). 시간이 지났다고 지우지 않는다. */
    private final Set<String> unreleasedTokens = ConcurrentHashMap.newKeySet();

    /** 재개 때 Set에서 찾지 못한 진행 중 실행의 token. 그 실행이 끝날 때까지 유효 용량에서 뺀다. */
    private final Set<String> uncountedTokens = ConcurrentHashMap.newKeySet();

    /** 이전 프로세스가 남긴 자기 인스턴스 token을 한 번 정리했는지. Redis가 안 되면 성공할 때까지 다음 회차에 다시 한다. */
    private volatile boolean previousRunSwept;

    /** true이면 Redis를 호출하지 않고 직행시킨다. 열린 breaker나 실패한 기동 확인 뒤에 켜지고, {@link #tryResume()}만 끈다. */
    private volatile boolean degraded;
    private volatile long degradedSinceNanos;

    /** auto-resume이 꺼져 있을 때 사람이 확인해 준 재개. 한 번의 재개에만 쓰인다. */
    private volatile boolean resumeApproved;

    public RedisSemaphoreGate(
        @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redisTemplate,
        CouponAdmissionProperties properties,
        LocalLimiter localLimiter
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.localLimiter = localLimiter;
        this.breaker = CircuitBreaker.of("coupon-admission-redis", breakerConfig(properties.breaker()));
        this.breaker.getEventPublisher().onStateTransition(event -> {
            if (event.getStateTransition().getToState() == CircuitBreaker.State.OPEN) {
                enterDegraded("CircuitBreaker가 열렸다");
            }
        });
    }

    /** 기동 시 Redis를 확인해 시작 모드를 정한다. 성공하면 정상(세마포어 경로), 실패하면 DEGRADED(직행)로 시작한다. */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            probeWriteRead();
            redisTemplate.opsForValue().setIfAbsent(EPOCH_KEY, epochValue());
            log.info("쿠폰 발급 수용: 기동 시 Redis 확인에 성공했다(세마포어 경로)");
        } catch (RuntimeException e) {
            enterDegraded("기동 시 Redis 확인에 실패했다: " + e.getMessage());
        }
    }

    @Override
    public Admission acquire() {
        String token = properties.instanceId() + ":" + UUID.randomUUID();
        return acquire(token);
    }

    Admission acquire(String token) {
        if (degraded) {
            return new Admission(Status.SKIPPED, null);
        }
        int effectiveCapacity = effectiveCapacity();
        if (effectiveCapacity < 1) {
            // 유효 용량이 없으면 Redis를 호출하지 않고 자리 없음으로 처리한다.
            return new Admission(Status.FULL, token);
        }
        if (!breaker.tryAcquirePermission()) {
            // Redis를 호출하지 않았으므로 token은 만들어지지 않았고 Set에도 없다.
            return new Admission(Status.SKIPPED, null);
        }
        liveTokens.add(token);
        long startedAt = System.nanoTime();
        try {
            Long result = redisTemplate.execute(ACQUIRE_SCRIPT, List.of(PERMITS_KEY), String.valueOf(effectiveCapacity), token);
            if (result != null && (result == 1L || result == 0L)) {
                breaker.onSuccess(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
                if (result == 1L) {
                    return new Admission(Status.ACQUIRED, token);
                }
                liveTokens.remove(token);
                return new Admission(Status.FULL, token);
            }
            IllegalStateException unexpected = new IllegalStateException("예상하지 못한 획득 결과: " + result);
            breaker.onError(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS, unexpected);
            return uncertain(token, unexpected);
        } catch (RuntimeException e) {
            breaker.onError(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS, e);
            return uncertain(token, e);
        }
    }

    @Override
    public void release(Admission admission) {
        if (admission.status() == Status.FULL || admission.status() == Status.SKIPPED) {
            return;
        }
        String token = admission.token();
        try {
            // 재개 때 Set에서 찾지 못했던 실행이 끝났다. 그만큼 유효 용량이 돌아온다(SREM도 아래에서 그대로 시도한다).
            uncountedTokens.remove(token);
            if (degraded || !breaker.tryAcquirePermission()) {
                // Redis를 호출하지 않았다. token이 남아 있을 수 있으므로 기록해 두고 복구 뒤 정리한다.
                unreleasedTokens.add(token);
                return;
            }
            long startedAt = System.nanoTime();
            try {
                redisTemplate.opsForSet().remove(PERMITS_KEY, token);
                breaker.onSuccess(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
                unreleasedTokens.remove(token);
            } catch (RuntimeException e) {
                breaker.onError(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS, e);
                // 쿠폰과 발급 수는 되돌리지 않는다. 자기 token만 기록해 두고 복구 뒤 정리한다.
                unreleasedTokens.add(token);
                log.warn("자리 반환 실패, 복구 뒤 정리할 token으로 기록한다: token={}, error={}", token, e.getMessage());
            }
        } finally {
            // 실행은 끝났다. 남아 있을 수 있는 token은 unreleasedTokens가 이어서 책임진다.
            liveTokens.remove(token);
        }
    }

    /**
     * 자기 token만 정리한다. DEGRADED이거나 Redis가 안 되거나 CircuitBreaker가 막으면 예외 없이 멈추고 다음 회차에 다시 한다
     * (DEGRADED의 정리는 {@link #tryResume()}이 재개 조건으로 한다).
     */
    @Override
    public void recoverOwnTokens() {
        if (degraded || (unreleasedTokens.isEmpty() && previousRunSwept)) {
            return;
        }
        if (!breaker.tryAcquirePermission()) {
            return;
        }
        long startedAt = System.nanoTime();
        try {
            cleanOwnTokens();
            breaker.onSuccess(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
        } catch (RuntimeException e) {
            breaker.onError(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS, e);
            log.warn("고아 token 정리를 다음 회차로 미룬다: {}", e.getMessage());
        }
    }

    /**
     * DEGRADED에서 안전 조건을 확인하고 세마포어 경로로 돌아온다. 실패하면 DEGRADED로 남고 최소 대기부터 다시 센다.
     * auto-resume이 꺼져 있으면 {@link #approveResume()}으로 사람이 확인해 주기 전에는 조건이 충족돼도 돌아오지 않는다.
     */
    @Override
    public void tryResume() {
        if (!degraded) {
            return;
        }
        if (!properties.autoResume() && !resumeApproved) {
            return;
        }
        Duration waited = Duration.ofNanos(System.nanoTime() - degradedSinceNanos);
        if (waited.compareTo(properties.breaker().waitDurationInOpenState()) < 0) {
            return;
        }
        if (!resumeLock.tryLock()) {
            return;
        }
        try {
            if (!degraded) {
                return;
            }
            resume();
        } catch (RuntimeException e) {
            // 조건을 채우지 못했다. DEGRADED로 남고 최소 대기를 다시 센다.
            degradedSinceNanos = System.nanoTime();
            log.warn("쿠폰 발급 수용: 재개 조건을 채우지 못해 직행을 유지한다: {}", e.getMessage());
        } finally {
            resumeLock.unlock();
        }
    }

    @Override
    public void approveResume() {
        resumeApproved = true;
    }

    private void resume() {
        // 1. 쓰기·읽기 왕복. PONG만으로는 쓰기가 막힌 Redis를 알아채지 못한다
        probeWriteRead();
        // 2. 유실 감지. sentinel 키가 사라졌다면 Redis 상태가 유실된 것이다
        boolean stateLost = !Boolean.TRUE.equals(redisTemplate.hasKey(EPOCH_KEY));
        // 3. 자기 token 정리. 남의 token과 Set 전체는 건드리지 않는다
        cleanOwnTokens();
        // 4. 용량 차감. 진행 중인 실행 중 Set에 token이 없는 것은 끝날 때까지 C에서 뺀다
        Set<String> missing = ConcurrentHashMap.newKeySet();
        for (String token : Set.copyOf(liveTokens)) {
            if (!Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(PERMITS_KEY, token))) {
                missing.add(token);
            }
        }
        uncountedTokens.clear();
        uncountedTokens.addAll(missing);
        redisTemplate.opsForValue().setIfAbsent(EPOCH_KEY, epochValue());
        if (stateLost) {
            log.warn("쿠폰 발급 수용: Redis 상태 유실을 감지했다(sentinel 없음). 진행 중인 {}개 실행을 용량에서 차감한다", missing.size());
        }
        // 5. 복귀. 시험 호출 수만큼만 Redis를 쓰고, 모두 성공하면 breaker가 닫힌다
        if (breaker.getState() == CircuitBreaker.State.OPEN) {
            breaker.transitionToHalfOpenState();
        }
        resumeApproved = false;
        degraded = false;
        log.warn("쿠폰 발급 수용: 안전 조건을 확인하고 세마포어 경로로 돌아온다(차감 {}개, 직행 진행 중 {}개)",
            missing.size(), localLimiter.inProgress());
    }

    private void probeWriteRead() {
        String value = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(PROBE_KEY, value, PROBE_TTL);
        String read = redisTemplate.opsForValue().get(PROBE_KEY);
        if (!value.equals(read)) {
            throw new IllegalStateException("Redis 쓰기·읽기 왕복 값이 다르다");
        }
    }

    private void cleanOwnTokens() {
        for (String token : Set.copyOf(unreleasedTokens)) {
            redisTemplate.opsForSet().remove(PERMITS_KEY, token);
            unreleasedTokens.remove(token);
        }
        if (!previousRunSwept) {
            sweepPreviousRunTokens();
            previousRunSwept = true;
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

    private void enterDegraded(String reason) {
        if (!degraded) {
            degradedSinceNanos = System.nanoTime();
            degraded = true;
            log.warn("쿠폰 발급 수용: Redis를 건너뛰고 DB로 직행한다(상한 C={}): {}", properties.capacity(), reason);
        }
    }

    /** 현재 활성 token 수(점유). 읽지 못하면 0이 아니라 예외를 그대로 올린다. 진단용이라 CircuitBreaker를 거치지 않는다. */
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

    /** 재개 때 Set에서 찾지 못해 유효 용량에서 빼 둔 진행 중 실행의 token. */
    public Set<String> uncountedTokens() {
        return Set.copyOf(uncountedTokens);
    }

    /** Redis를 건너뛰고 직행 중이면 true. */
    public boolean isDegraded() {
        return degraded;
    }

    /** Redis 호출 CircuitBreaker. 상태 확인과 테스트 사이의 초기화에 쓴다. */
    public CircuitBreaker circuitBreaker() {
        return breaker;
    }

    private static CircuitBreakerConfig breakerConfig(CouponAdmissionProperties.Breaker settings) {
        return CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(settings.slidingWindowSize())
            .minimumNumberOfCalls(settings.minimumNumberOfCalls())
            .failureRateThreshold(settings.failureRateThreshold())
            .waitDurationInOpenState(settings.waitDurationInOpenState())
            .permittedNumberOfCallsInHalfOpenState(settings.permittedCallsInHalfOpen())
            .build();
    }

    private static DefaultRedisScript<Long> acquireScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/coupon-admission-acquire.lua"));
        script.setResultType(Long.class);
        return script;
    }

    /** C에서 Set에 token이 없는 진행 중 실행(재개 때 못 찾은 실행, 직행 실행)을 뺀 값. */
    private int effectiveCapacity() {
        return properties.capacity() - uncountedTokens.size() - localLimiter.inProgress();
    }

    private String epochValue() {
        return properties.instanceId() + ":" + System.currentTimeMillis();
    }

    private Admission uncertain(String token, RuntimeException cause) {
        // 응답을 못 받았어도 token이 Set에 들어갔을 수 있다. 이 실행은 직행으로 가므로 진행 중 목록에서 빼고 미반환으로 기록한다.
        liveTokens.remove(token);
        unreleasedTokens.add(token);
        log.warn("세마포어 획득 결과를 알 수 없다: token={}, error={}", token, cause.getMessage());
        return new Admission(Status.UNCERTAIN, token);
    }
}
