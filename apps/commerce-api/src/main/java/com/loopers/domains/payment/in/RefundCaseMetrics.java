package com.loopers.domains.payment.in;

import com.loopers.domains.payment.app.port.RefundCaseCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// 환불 확인 대상 상태별 gauge. 상태 6개를 시작부터 등록하고 label은 알림 규칙이 쓰는 소문자다.
// 첫 갱신 전 값은 NaN이다(0이면 "대상 없음"으로 읽힌다). 갱신이 실패하면 마지막 값을 그대로 둔다.
// 갱신은 따로 스레드를 두지 않고 미확인 결제 gauge 갱신 작업이 함께 부른다(계획 P6).
@Slf4j
@Component
public class RefundCaseMetrics {
    static final String METRIC_NAME = "commerce.payment.refund_cases";
    private static final String DESCRIPTION = "환불 확인 대상의 status별 수. DB를 주기 조회하므로 갱신 주기만큼 늦다. "
        + "인스턴스마다 같은 값을 내므로 합하지 말고 max로 본다.";
    private static final List<String> TRACKED_STATUSES = List.of("TRACKING", "REFUND_REQUESTED", "REFUND_UNKNOWN", "REFUNDED",
        "CLOSED_NO_CHARGE", "HANDED_TO_HUMAN");

    private final RefundCaseCounter refundCaseCounter;
    private final Map<String, AtomicReference<Double>> values;

    public RefundCaseMetrics(RefundCaseCounter refundCaseCounter, MeterRegistry meterRegistry) {
        this.refundCaseCounter = refundCaseCounter;
        Map<String, AtomicReference<Double>> registered = new HashMap<>();
        for (String status : TRACKED_STATUSES) {
            AtomicReference<Double> value = new AtomicReference<>(Double.NaN);
            Gauge.builder(METRIC_NAME, value, AtomicReference::get)
                .description(DESCRIPTION)
                .tag("status", status.toLowerCase(Locale.ROOT))
                .register(meterRegistry);
            registered.put(status, value);
        }
        this.values = Map.copyOf(registered);
    }

    public void refresh() {
        Map<String, Long> counts;
        try {
            counts = refundCaseCounter.countByStatus();
        } catch (RuntimeException exception) {
            log.warn("환불 확인 대상 지표 갱신에 실패해 마지막 값을 유지합니다. causeType={}", exception.getClass().getSimpleName());
            return;
        }
        Set<String> untracked = new HashSet<>(counts.keySet());
        untracked.removeAll(TRACKED_STATUSES);
        if (!untracked.isEmpty()) {
            log.warn("환불 확인 대상 지표가 모르는 status가 있어 값에 넣지 않습니다. statuses={}", untracked);
        }
        values.forEach((status, value) -> value.set(counts.getOrDefault(status, 0L).doubleValue()));
    }
}
