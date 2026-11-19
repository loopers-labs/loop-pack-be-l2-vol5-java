package com.loopers.infrastructure.coupon;

import com.loopers.config.coupon.CouponAdmissionProperties;
import com.loopers.config.coupon.CouponAdmissionRedisConfig;
import com.loopers.domain.coupon.CouponAdmissionGate;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 인스턴스 메모리 gate (8주차 설계 §7-3, D10·D14).
 * - open 은 "SET gen 성공 → 메모리에 열림 기록" 순서다. SET 이 실패하면 메모리는 닫힌 채라 다음 요청도 503 으로 끝난다.
 * - open 은 기존 키를 지우지 않는다. 자리 기록을 지우면 실행 중인 발급이 기록 없이 돌아 C 를 넘을 수 있다.
 * - 닫힐 때 세대값을 지우지 않고 보존한다. 자동 재개(probeOnce)가 "기억한 세대 == Redis 의 세대"를 확인하는 근거다.
 * - 캠페인별 상태 전이는 synchronized 로 직렬화한다. probe 스케줄러와 요청 경로의 forceClose 가 겹칠 수 있다.
 */
@Slf4j
@Component
public class RedisCouponAdmissionGate implements CouponAdmissionGate {

    private record State(boolean open, String generation, Closure closure, int probeSuccesses) {

        static State opened(String generation) {
            return new State(true, generation, null, 0);
        }

        State closed(CloseReason reason, Instant at) {
            return new State(false, generation, new Closure(reason, at), 0);
        }

        State withProbeSuccesses(int count) {
            return new State(open, generation, closure, count);
        }
    }

    static final String GATE_OPEN = "coupon.admission.gate_open";
    static final String GATE_CLOSE = "coupon.admission.gate_close";
    static final String AUTO_REOPEN = "coupon.admission.auto_reopen";

    private final RedisTemplate<String, String> redisTemplate;
    private final CouponAdmissionProperties properties;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final Map<Long, State> states = new ConcurrentHashMap<>();
    /** 캠페인별 자동 재개 시각. window 안의 횟수만 의미가 있어 오래된 것은 probe 때 버린다. */
    private final Map<Long, Deque<Instant>> autoReopens = new ConcurrentHashMap<>();

    public RedisCouponAdmissionGate(
        @Qualifier(CouponAdmissionRedisConfig.COUPON_ADMISSION_REDIS_TEMPLATE) RedisTemplate<String, String> redisTemplate,
        CouponAdmissionProperties properties,
        Clock clock,
        MeterRegistry meterRegistry
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    /** 상태를 바꿀 때마다 거친다. 캠페인을 처음 보면 gate_open Gauge(열림 1 / 닫힘 0)를 등록한다. */
    private void put(Long campaignId, State state) {
        if (states.put(campaignId, state) == null) {
            Gauge.builder(GATE_OPEN, states, map -> map.get(campaignId).open() ? 1 : 0)
                .tag("campaign", String.valueOf(campaignId))
                .register(meterRegistry);
        }
    }

    @Override
    public boolean isOpen(Long campaignId) {
        State state = states.get(campaignId);
        return state != null && state.open();
    }

    @Override
    public Optional<String> generation(Long campaignId) {
        State state = states.get(campaignId);
        return state == null || !state.open() ? Optional.empty() : Optional.of(state.generation());
    }

    @Override
    public Optional<Closure> closure(Long campaignId) {
        State state = states.get(campaignId);
        return state == null || state.open() ? Optional.empty() : Optional.of(state.closure());
    }

    @Override
    public boolean generationMatches(Long campaignId) {
        State state = states.get(campaignId);
        if (state == null || state.generation() == null) {
            return false;
        }
        return state.generation().equals(redisTemplate.opsForValue().get(CouponAdmissionKeys.gen(campaignId)));
    }

    @Override
    public synchronized int recentAutoReopens(Long campaignId) {
        Deque<Instant> recent = autoReopens.get(campaignId);
        if (recent == null) {
            return 0;
        }
        Instant cutoff = clock.instant().minus(properties.autoReopen().window());
        return (int) recent.stream().filter(at -> !at.isBefore(cutoff)).count();
    }

    @Override
    public Set<Long> campaignIds() {
        return Set.copyOf(states.keySet());
    }

    @Override
    public synchronized String open(Long campaignId) {
        String generation = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(CouponAdmissionKeys.gen(campaignId), generation);
        put(campaignId, State.opened(generation));
        log.info("쿠폰 접수 gate 를 연다. campaignId={} generation={}", campaignId, generation);
        return generation;
    }

    @Override
    public synchronized void close(Long campaignId) {
        closeWith(campaignId, CloseReason.ADMIN);
        log.info("관리자가 쿠폰 접수 gate 를 닫는다. campaignId={}", campaignId);
    }

    @Override
    public synchronized void forceClose(Long campaignId, CloseReason reason) {
        closeWith(campaignId, reason);
        log.warn("Redis 상태가 불확실해 쿠폰 접수 gate 를 닫는다. 관리자 open 전까지 접수·승격·claim 은 503 이다. campaignId={} reason={}", campaignId, reason);
    }

    private void closeWith(Long campaignId, CloseReason reason) {
        State current = states.getOrDefault(campaignId, State.opened(null));
        put(campaignId, current.closed(reason, clock.instant()));
        meterRegistry.counter(GATE_CLOSE, "reason", reason.name().toLowerCase()).increment();
    }

    @Override
    public synchronized boolean probeOnce(Long campaignId) {
        CouponAdmissionProperties.AutoReopen settings = properties.autoReopen();
        State state = states.get(campaignId);
        if (!settings.enabled() || state == null || state.open() || state.closure().reason() != CloseReason.REDIS_ERROR) {
            return false;
        }

        String actual;
        try {
            actual = redisTemplate.opsForValue().get(CouponAdmissionKeys.gen(campaignId));
        } catch (DataAccessException e) {
            states.put(campaignId, state.withProbeSuccesses(0));
            log.warn("자동 재개 probe 중 Redis 예외. 연속 일치 횟수를 0 으로 되돌린다. campaignId={}", campaignId, e);
            return false;
        }
        if (actual == null || !actual.equals(state.generation())) {
            closeWith(campaignId, CloseReason.STALE_GENERATION);
            log.warn("자동 재개 probe 에서 세대 불일치. 자동 재개를 중단한다. campaignId={} expected={} actual={}", campaignId, state.generation(), actual);
            return false;
        }

        int successes = state.probeSuccesses() + 1;
        if (successes < settings.requiredSuccesses()) {
            states.put(campaignId, state.withProbeSuccesses(successes));
            return false;
        }

        Instant now = clock.instant();
        Deque<Instant> recent = autoReopens.computeIfAbsent(campaignId, id -> new ArrayDeque<>());
        while (!recent.isEmpty() && recent.peekFirst().isBefore(now.minus(settings.window()))) {
            recent.pollFirst();
        }
        if (recent.size() >= settings.maxReopens()) {
            closeWith(campaignId, CloseReason.FLAPPING);
            log.warn("자동 재개가 window 안에 {}회를 넘었다. 관리자 open 을 기다린다. campaignId={}", settings.maxReopens(), campaignId);
            return false;
        }

        recent.addLast(now);
        put(campaignId, State.opened(state.generation()));
        meterRegistry.counter(AUTO_REOPEN).increment();
        log.info("자동 재개. 세대가 연속 {}회 일치해 gate 를 연다(세대 유지). campaignId={} generation={}", successes, campaignId, state.generation());
        return true;
    }
}
