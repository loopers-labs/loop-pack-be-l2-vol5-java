package com.loopers.application.payment;

import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.UnresolvedStatusSummary;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 미종결 결제의 업무 지표 (10주차 설계 3-1-2).
 * <p>
 * 스케줄러가 {@link #refresh()} 로 DB 를 집계해 불변 스냅샷을 통째로 교체하고, Gauge 는 그 스냅샷만 읽는다.
 * 그래서 Prometheus 의 scrape 가 DB 를 때리지 않고, 집계 실패가 scrape 응답을 깨뜨리지 않는다.
 * <p>
 * 관찰 범위: 전체 계정의 payment_attempts 중 REQUESTED·PENDING·UNKNOWN. SUCCESS·FAILED 는 세지 않는다.
 * 갱신 주기: PaymentUnresolvedMetricsScheduler 의 fixedDelay (기본 10초). 즉 지표는 지연된 근사다.
 * <p>
 * 다섯 상태를 구분한다.
 * 미수집(최초 집계 전)은 NaN, 실제 0 건은 0, 정상 갱신은 실제 값,
 * 집계 실패는 마지막 성공값을 유지하며 refresh{result=failure} 가 오르고 마지막 성공 시각이 과거에 멈춘다.
 * 낡음은 PromQL 의 time() - 마지막 성공 시각으로 판단한다. 경과 시간을 여기서 계산해 노출하지 않는다.
 * <p>
 * 다중 인스턴스: 이 값은 인스턴스 상태가 아니라 공용 DB 전체의 상태다.
 * sum 으로 모으면 인스턴스 수만큼 중복 합산되고, max 만 쓰면 낡은 스냅샷이 남을 수 있다.
 * 올바른 질의는 마지막 성공 시각으로 최신성을 걸러낸 뒤 max by (state) 다 (runbook 참고).
 */
@Slf4j
@Component
public class PaymentUnresolvedMetrics {

    static final String UNRESOLVED = "commerce.payment.unresolved";
    static final String OLDEST_CREATED_TIMESTAMP = "commerce.payment.unresolved.oldest.created.timestamp";
    static final String LAST_SUCCESS_TIMESTAMP = "commerce.payment.unresolved.refresh.last.success.timestamp";
    static final String REFRESH = "commerce.payment.unresolved.refresh";
    private static final String SECONDS = "seconds";

    private final PaymentAttemptRepository paymentAttemptRepository;
    private final Clock clock;
    private final Counter refreshSuccess;
    private final Counter refreshFailure;

    /**
     * 스냅샷 참조. Micrometer Gauge 는 대상을 약한 참조로 들고 있으므로 싱글턴 빈의 final 필드로 둬서 GC 를 막는다.
     */
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();

    public PaymentUnresolvedMetrics(
        PaymentAttemptRepository paymentAttemptRepository,
        MeterRegistry meterRegistry,
        Clock clock
    ) {
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.clock = clock;
        this.refreshSuccess = Counter.builder(REFRESH)
            .description("미종결 결제 집계 시도 결과")
            .tag("result", "success")
            .register(meterRegistry);
        this.refreshFailure = Counter.builder(REFRESH)
            .description("미종결 결제 집계 시도 결과")
            .tag("result", "failure")
            .register(meterRegistry);

        // Gauge 는 여기서 한 번만 등록한다. 갱신마다 재등록하지 않는다.
        for (PaymentAttempt.Status status : Snapshot.UNRESOLVED_STATUSES) {
            String state = status.name().toLowerCase(Locale.ROOT);
            Gauge.builder(UNRESOLVED, snapshot, holder -> count(holder.get(), status))
                .description("미종결 결제 시도 건수. DB 전체 기준이며 집계 주기만큼 지연된다")
                .tag("state", state)
                .register(meterRegistry);
            Gauge.builder(OLDEST_CREATED_TIMESTAMP, snapshot, holder -> oldestCreatedAt(holder.get(), status))
                .description("그 상태의 미종결 시도 중 가장 먼저 생성된 시도의 생성 시각(Unix epoch). 상태에 머문 시간이 아니다")
                .baseUnit(SECONDS)
                .tag("state", state)
                .register(meterRegistry);
        }
        Gauge.builder(LAST_SUCCESS_TIMESTAMP, snapshot, holder -> lastSuccessAt(holder.get()))
            .description("마지막으로 성공한 미종결 결제 집계 시각(Unix epoch). 경과 시간은 PromQL 의 time() 과의 차로 계산한다")
            .baseUnit(SECONDS)
            .register(meterRegistry);
    }

    /**
     * DB 를 한 번 집계해 스냅샷을 통째로 교체한다.
     * 실패하면 스냅샷을 건드리지 않는다. 마지막 성공값과 마지막 성공 시각이 그대로 유지되고 실패만 센다.
     * 실패를 0 으로 쓰면 "미확인 없음"이라는 거짓 안전 신호가 되기 때문이다.
     */
    public void refresh() {
        try {
            List<UnresolvedStatusSummary> summaries = paymentAttemptRepository.summarizeUnresolved();
            snapshot.set(new Snapshot(summaries, ZonedDateTime.now(clock)));
            refreshSuccess.increment();
        } catch (RuntimeException e) {
            refreshFailure.increment();
            log.warn("payment.unresolved.refresh phase=failed reason={}", e.getClass().getSimpleName(), e);
        }
    }

    private static double count(Snapshot current, PaymentAttempt.Status status) {
        if (current == null) {
            return Double.NaN;
        }
        return current.summaryOf(status).count();
    }

    private static double oldestCreatedAt(Snapshot current, PaymentAttempt.Status status) {
        if (current == null) {
            return Double.NaN;
        }
        ZonedDateTime oldest = current.summaryOf(status).oldestCreatedAt();
        return oldest == null ? Double.NaN : epochSeconds(oldest);
    }

    private static double lastSuccessAt(Snapshot current) {
        return current == null ? Double.NaN : epochSeconds(current.refreshedAt());
    }

    private static double epochSeconds(ZonedDateTime at) {
        return at.toInstant().toEpochMilli() / 1_000d;
    }

    /**
     * 한 번의 집계에서 나온 값만 담는 불변 스냅샷. 부분 갱신이 없으므로 내부 값들은 항상 같은 시점이다.
     * 다만 Gauge 는 scrape 때 각자 참조를 읽으므로, 교체 순간에 걸리면 한 응답 안에 두 스냅샷이 섞일 수 있다.
     * 값의 차이가 한 주기라 이 지표의 용도에서 무해하다고 보고 수용한다 (설계 3-1-2).
     */
    record Snapshot(List<UnresolvedStatusSummary> summaries, ZonedDateTime refreshedAt) {

        static final List<PaymentAttempt.Status> UNRESOLVED_STATUSES = List.of(
            PaymentAttempt.Status.REQUESTED, PaymentAttempt.Status.PENDING, PaymentAttempt.Status.UNKNOWN);

        UnresolvedStatusSummary summaryOf(PaymentAttempt.Status status) {
            return summaries.stream()
                .filter(summary -> status == summary.status())
                .findFirst()
                .orElseGet(() -> new UnresolvedStatusSummary(status, 0L, null));
        }
    }
}
