package com.loopers.domains.order.out;

import com.loopers.domains.order.app.port.PaymentApplyFailureCounter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

// 네 source를 시작 시 모두 등록해 아직 0인 경로도 시계열로 보이게 한다. 주문 수만큼 늘어나지 않도록 orderId는 태그로 넣지 않는다.
@Component
public class MicrometerPaymentApplyFailureCounter implements PaymentApplyFailureCounter {
    static final String METRIC_NAME = "commerce.payment.apply_failed";
    private static final String DESCRIPTION = "결제 결과 반영 트랜잭션이 실패해 APPLY_FAILED 이상을 남긴 횟수, source별. 이상 기록 commit 뒤에만 센다.";

    private final Map<Source, Counter> counters;

    public MicrometerPaymentApplyFailureCounter(MeterRegistry meterRegistry) {
        Map<Source, Counter> registered = new EnumMap<>(Source.class);
        for (Source source : Source.values()) {
            registered.put(source, Counter.builder(METRIC_NAME)
                .description(DESCRIPTION)
                .tag("source", source.name().toLowerCase(Locale.ROOT))
                .register(meterRegistry));
        }
        this.counters = Map.copyOf(registered);
    }

    @Override
    public void record(Source source) {
        counters.get(source).increment();
    }
}
