package com.loopers.infrastructure.order;

import com.loopers.domain.order.AwaitingPaymentCounts;
import com.loopers.domain.order.OrderRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 업무 지표 commerce.payment.awaiting (Prometheus: commerce_payment_awaiting). 결제 대기(AWAITING_PAYMENT) 주문 수.
 * 태그 pg_transaction 은 두 값뿐이다: known(PG 가 접수해 거래 키가 있음) · missing(거래 키 없음 — PG 결과 미확인 UNKNOWN, 또는 결제 시작 직후 PG 응답 대기 중).
 * 주문·사용자 식별자는 태그로 쓰지 않는다. 개별 주문은 로그·DB 로 찾는다.
 * 값은 scrape 때 DB 를 읽지 않고 commerce.metrics.payment-awaiting.refresh-interval 마다 DB 를 한 번 세어 둔 값이다: 최대 그 주기만큼 늦다.
 * 첫 갱신 전이나 마지막 갱신이 실패했으면 NaN (0 과 구분: 0 은 "결제 대기 없음"을 확인한 값).
 */
@Slf4j
@Component
public class PaymentAwaitingMetrics {
    static final String METRIC = "commerce.payment.awaiting";
    static final String TAG = "pg_transaction";
    static final String KNOWN = "known";
    static final String MISSING = "missing";

    private final OrderRepository orderRepository;
    private volatile double known = Double.NaN;
    private volatile double missing = Double.NaN;

    public PaymentAwaitingMetrics(OrderRepository orderRepository, MeterRegistry meterRegistry) {
        this.orderRepository = orderRepository;
        Gauge.builder(METRIC, this, metrics -> metrics.known)
            .description("결제 대기(AWAITING_PAYMENT) 주문 수, PG 가 접수해 거래 키가 있는 것. DB 를 주기적으로 센 값")
            .tag(TAG, KNOWN)
            .register(meterRegistry);
        Gauge.builder(METRIC, this, metrics -> metrics.missing)
            .description("결제 대기(AWAITING_PAYMENT) 주문 수, 거래 키가 없는 것 (PG 결과 미확인·응답 대기). DB 를 주기적으로 센 값")
            .tag(TAG, MISSING)
            .register(meterRegistry);
    }

    /** DB 를 세어 gauge 값을 바꾼다. 실패하면 두 값을 NaN 으로 두고 다음 주기에 다시 센다. */
    @Scheduled(fixedDelayString = "${commerce.metrics.payment-awaiting.refresh-interval}",
        initialDelayString = "${commerce.metrics.payment-awaiting.refresh-interval}")
    public void refresh() {
        try {
            AwaitingPaymentCounts counts = orderRepository.countAwaitingPayment();
            known = counts.withTransactionKey();
            missing = counts.withoutTransactionKey();
        } catch (RuntimeException e) {
            known = Double.NaN;
            missing = Double.NaN;
            log.warn("결제 대기 지표 갱신 실패, 값을 NaN 으로 둔다", e);
        }
    }
}
