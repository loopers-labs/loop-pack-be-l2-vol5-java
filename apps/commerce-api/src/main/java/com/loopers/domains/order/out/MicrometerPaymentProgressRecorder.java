package com.loopers.domains.order.out;

import com.loopers.domains.order.app.port.PaymentProgressRecorder;
import com.loopers.domains.payment.app.port.PaymentGateway;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

// 알림 비율(결과 모름 유입 / 결제 시작)의 분자·분모다. label은 유한한 providerId뿐이고 orderId는 넣지 않는다.
// PG 어댑터 빈마다 시작 시 0으로 등록해 아직 사건이 없어도 시계열이 보이게 한다(out은 app.port만 쓸 수 있어 레지스트리 대신 어댑터에서 읽는다).
// 기록은 commit 뒤에 불리므로 예외를 내지 않는다. 등록되지 않은 providerId도 같은 이름으로 등록해 센다.
@Component
public class MicrometerPaymentProgressRecorder implements PaymentProgressRecorder {
    static final String STARTED = "commerce.payment.started";
    static final String UNKNOWN_ENTERED = "commerce.payment.unknown_entered";
    private static final String STARTED_DESCRIPTION = "PG 결제 시작 트랜잭션이 commit된 횟수, provider별. 같은 시도의 재요청은 세지 않는다.";
    private static final String UNKNOWN_ENTERED_DESCRIPTION =
        "결제 기록이 UNKNOWN이 아닌 상태에서 UNKNOWN으로 바뀐 commit 횟수, provider별. 만료가 미확인을 빼 가도 줄지 않는다.";

    private final MeterRegistry meterRegistry;

    public MicrometerPaymentProgressRecorder(MeterRegistry meterRegistry, ObjectProvider<PaymentGateway> gateways) {
        this.meterRegistry = meterRegistry;
        gateways.orderedStream().map(PaymentGateway::providerId).distinct().forEach(providerId -> {
            startedCounter(providerId);
            unknownEnteredCounter(providerId);
        });
    }

    @Override
    public void started(String providerId) {
        startedCounter(providerId).increment();
    }

    @Override
    public void unknownEntered(String providerId) {
        unknownEnteredCounter(providerId).increment();
    }

    private Counter startedCounter(String providerId) {
        return counter(STARTED, STARTED_DESCRIPTION, providerId);
    }

    private Counter unknownEnteredCounter(String providerId) {
        return counter(UNKNOWN_ENTERED, UNKNOWN_ENTERED_DESCRIPTION, providerId);
    }

    // 같은 이름·태그면 Micrometer가 이미 등록된 counter를 돌려준다.
    private Counter counter(String name, String description, String providerId) {
        return Counter.builder(name)
            .description(description)
            .tag("provider", providerId)
            .register(meterRegistry);
    }
}
