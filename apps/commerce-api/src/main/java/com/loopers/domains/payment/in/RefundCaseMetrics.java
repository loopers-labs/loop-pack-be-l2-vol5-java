package com.loopers.domains.payment.in;

import com.loopers.domains.payment.app.port.RefundCaseCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// 환불 확인 대상 gauge(10주차 5b 계획 4절). 돈의 상태 6개를 시작부터 등록하고 label은 알림 규칙이 쓰는 소문자다.
// 첫 갱신 전 값은 NaN이다(0이면 "대상 없음"으로 읽힌다). 갱신이 실패하면 세 gauge 모두 마지막 값을 그대로 둔다.
// 갱신은 따로 스레드를 두지 않고 미확인 결제 gauge 갱신 작업이 함께 부른다(계획 P6).
@Slf4j
@Component
public class RefundCaseMetrics {
    static final String CASES_METRIC = "commerce.payment.refund_cases";
    static final String PERSON_OPEN_METRIC = "commerce.payment.refund_person_open";
    static final String OLDEST_AGE_METRIC = "commerce.payment.refund_requested_oldest_age_seconds";
    private static final String SCRAPE_NOTE = " DB를 주기 조회하므로 갱신 주기만큼 늦다. 인스턴스마다 같은 값을 내므로 합하지 말고 max로 본다.";
    private static final List<String> MONEY_STATUSES = List.of("CHECKING", "CHARGED", "REFUND_REQUESTED", "REFUND_UNKNOWN",
        "REFUNDED", "NO_CHARGE");

    private final RefundCaseCounter refundCaseCounter;
    private final Clock clock;
    private final Map<String, AtomicReference<Double>> cases;
    private final AtomicReference<Double> personOpen = new AtomicReference<>(Double.NaN);
    private final AtomicReference<Double> oldestAgeSeconds = new AtomicReference<>(Double.NaN);

    public RefundCaseMetrics(RefundCaseCounter refundCaseCounter, MeterRegistry meterRegistry, Clock clock) {
        this.refundCaseCounter = refundCaseCounter;
        this.clock = clock;
        Map<String, AtomicReference<Double>> registered = new HashMap<>();
        for (String moneyStatus : MONEY_STATUSES) {
            AtomicReference<Double> value = new AtomicReference<>(Double.NaN);
            Gauge.builder(CASES_METRIC, value, AtomicReference::get)
                .description("환불 확인 대상의 돈의 상태별 수." + SCRAPE_NOTE)
                .tag("money_status", moneyStatus.toLowerCase(Locale.ROOT))
                .register(meterRegistry);
            registered.put(moneyStatus, value);
        }
        this.cases = Map.copyOf(registered);
        Gauge.builder(PERSON_OPEN_METRIC, personOpen, AtomicReference::get)
            .description("사람 담당 중(담당 종료 전)인 환불 확인 대상 수." + SCRAPE_NOTE)
            .register(meterRegistry);
        Gauge.builder(OLDEST_AGE_METRIC, oldestAgeSeconds, AtomicReference::get)
            .description("환불 요청함 대상 중 가장 오래된 요청의 경과 초, 없으면 0." + SCRAPE_NOTE)
            .register(meterRegistry);
    }

    public void refresh() {
        RefundCaseCounter.Snapshot snapshot;
        try {
            snapshot = refundCaseCounter.snapshot();
        } catch (RuntimeException exception) {
            log.warn("환불 확인 대상 지표 갱신에 실패해 마지막 값을 유지합니다. causeType={}", exception.getClass().getSimpleName());
            return;
        }
        Map<String, Long> counts = snapshot.countsByMoneyStatus();
        Set<String> untracked = new HashSet<>(counts.keySet());
        untracked.removeAll(MONEY_STATUSES);
        if (!untracked.isEmpty()) {
            log.warn("환불 확인 대상 지표가 모르는 돈의 상태가 있어 값에 넣지 않습니다. moneyStatuses={}", untracked);
        }
        cases.forEach((moneyStatus, value) -> value.set(counts.getOrDefault(moneyStatus, 0L).doubleValue()));
        personOpen.set((double) snapshot.personOpen());
        Instant now = clock.instant();
        oldestAgeSeconds.set(snapshot.oldestRefundRequestedAt()
            .map(requestedAt -> (double) Math.max(0, Duration.between(requestedAt, now).getSeconds()))
            .orElse(0.0));
    }
}
