package com.loopers.domains.order.in;

import com.loopers.domains.order.app.port.UnresolvedPaymentCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// 미확인 결제 gauge. 주문이 PAYMENT_PENDING일 수 있는 PG 상태는 이 네 개뿐이라 네 시계열을 시작부터 등록한다.
// 첫 갱신 전 값은 NaN이다. 0이면 "미확인 없음"으로 읽히기 때문이다. 갱신이 실패하면 마지막 값을 그대로 둔다.
// Micrometer gauge는 대상 객체를 약하게 참조하므로 값 보관 객체는 이 빈의 필드로 붙잡아 둔다.
@Slf4j
@Component
public class UnresolvedPaymentMetrics {
    static final String METRIC_NAME = "commerce.payment.unresolved";
    private static final String DESCRIPTION = "PAYMENT_PENDING인 PG 결제 기록의 provider_status별 수. DB를 주기 조회하므로 갱신 주기만큼 늦다. "
        + "인스턴스마다 같은 값을 내므로 합하지 말고 max로 본다.";
    private static final Set<String> TRACKED_STATUSES = Set.of("SEND_STARTED", "PENDING", "UNKNOWN", "NEEDS_REVIEW");

    private final UnresolvedPaymentCounter unresolvedPaymentCounter;
    private final Map<String, AtomicReference<Double>> values;

    public UnresolvedPaymentMetrics(UnresolvedPaymentCounter unresolvedPaymentCounter, MeterRegistry meterRegistry) {
        this.unresolvedPaymentCounter = unresolvedPaymentCounter;
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
            counts = unresolvedPaymentCounter.countByProviderStatus();
        } catch (RuntimeException exception) {
            log.warn("미확인 결제 지표 갱신에 실패해 마지막 값을 유지합니다. causeType={}", exception.getClass().getSimpleName());
            return;
        }
        Set<String> untracked = new HashSet<>(counts.keySet());
        untracked.removeAll(TRACKED_STATUSES);
        if (!untracked.isEmpty()) {
            log.warn("미확인 결제 지표 대상이 아닌 provider_status가 결제 대기 주문에 있어 값에 넣지 않습니다. statuses={}", untracked);
        }
        values.forEach((status, value) -> value.set(counts.getOrDefault(status, 0L).doubleValue()));
    }
}
