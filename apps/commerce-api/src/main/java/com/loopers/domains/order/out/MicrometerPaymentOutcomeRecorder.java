package com.loopers.domains.order.out;

import com.loopers.domains.order.app.port.PaymentOutcomeRecorder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

// 네 결과를 시작 시 모두 등록해 아직 0인 결과도 시계열로 보이게 한다. 주문 수만큼 늘어나지 않도록 orderId는 태그로 넣지 않는다.
@Component
public class MicrometerPaymentOutcomeRecorder implements PaymentOutcomeRecorder {
    static final String METRIC_NAME = "commerce.payment.outcome";
    private static final String DESCRIPTION = "주문이 결제 종결로 처음 바뀐 횟수, result별. commit 뒤에만 센다.";

    private final Map<Outcome, Counter> counters;

    public MicrometerPaymentOutcomeRecorder(MeterRegistry meterRegistry) {
        Map<Outcome, Counter> registered = new EnumMap<>(Outcome.class);
        for (Outcome outcome : Outcome.values()) {
            registered.put(outcome, Counter.builder(METRIC_NAME)
                .description(DESCRIPTION)
                .tag("result", outcome.name().toLowerCase(Locale.ROOT))
                .register(meterRegistry));
        }
        this.counters = Map.copyOf(registered);
    }

    @Override
    public void record(Outcome outcome) {
        counters.get(outcome).increment();
    }
}
