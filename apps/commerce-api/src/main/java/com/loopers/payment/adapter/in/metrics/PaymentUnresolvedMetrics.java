package com.loopers.payment.adapter.in.metrics;

import com.loopers.payment.application.PaymentQueryService;
import com.loopers.payment.domain.PaymentStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 업무 지표 (W10 plan.md D-4): 결과를 모르는 결제 수. health는 "서버가 살아 있나"만 답하고, 이 값은 "결제가 끝났나"에 답한다.
 * <ul>
 *   <li>gauge — 사고 때 늘고 복구 뒤 줄어야 하는 지금 값이다 (누적 counter가 아니다).</li>
 *   <li>/actuator/prometheus를 읽을 때마다 DB에서 센다. 따로 갱신 주기가 없다.</li>
 *   <li>label은 status 두 값뿐이다. 어느 주문인지는 로그·SQL에서 찾는다 (orderId·userId label 금지).</li>
 * </ul>
 */
@RequiredArgsConstructor
@Component
public class PaymentUnresolvedMetrics implements MeterBinder {

    static final String NAME = "commerce.payment.unresolved";
    private static final List<PaymentStatus> UNRESOLVED = List.of(PaymentStatus.PENDING, PaymentStatus.UNKNOWN);

    private final PaymentQueryService paymentQueryService;

    @Override
    public void bindTo(MeterRegistry registry) {
        for (PaymentStatus status : UNRESOLVED) {
            // 서비스를 weak reference로 넘긴다. Spring Boot는 registry를 전역 registry(Metrics.globalRegistry)에 넣고 빼지 않으므로,
            // 람다가 서비스를 붙잡으면 닫힌 Spring 문맥(테스트의 @DirtiesContext 등)이 통째로 GC되지 않는다.
            Gauge.builder(NAME, paymentQueryService, service -> service.countByStatus(status))
                .tag("status", status.name())
                .description("결과를 모르는 결제 수 (PENDING: PG 처리 중, UNKNOWN: 생성 결과 모름). 서버마다 같은 전체 수를 보고하므로 합이 아니라 최댓값으로 본다")
                .register(registry);
        }
    }
}
