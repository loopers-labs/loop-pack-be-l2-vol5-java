package com.loopers.payment.adapter.in.metrics;

import com.loopers.common.domain.Money;
import com.loopers.payment.adapter.out.persistence.PaymentJpaRepository;
import com.loopers.payment.domain.ExternalPaymentStatus;
import com.loopers.payment.domain.Payment;
import com.loopers.payment.domain.PaymentObservation;
import com.loopers.utils.DatabaseCleanUp;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 업무 지표 (W10 plan.md D-4). 지표는 앱이 뜰 때 한 번 등록되고, 값은 읽을 때마다 DB의 결제 상태를 센다.
 * 결제는 도메인 메서드로 상태를 옮긴 뒤 저장한다 — 발송·PG는 거치지 않는다.
 */
@SpringBootTest
class PaymentUnresolvedMetricsIntegrationTest {

    private static final ZonedDateTime NOW = ZonedDateTime.parse("2026-11-25T10:00:00+09:00");
    private static final long AMOUNT = 1000L;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private PaymentJpaRepository paymentJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("결과를 모르는 결제 수를 PENDING·UNKNOWN별로 센다. 종결·발송 전 결제는 세지 않는다.")
    @Test
    void countsUnresolvedPaymentsByStatus() {
        // given — 앱이 뜬 뒤에 생긴 결제들
        unknown(1L);
        unknown(2L);
        pending(3L);
        succeeded(4L);
        requested(5L);

        // when
        double unknownCount = gauge("UNKNOWN").value();
        double pendingCount = gauge("PENDING").value();

        // then
        assertThat(unknownCount).isEqualTo(2.0);
        assertThat(pendingCount).isEqualTo(1.0);
    }

    @DisplayName("결과를 모르던 결제가 SUCCESS가 되면 다음에 읽을 때 그 수가 빠진다.")
    @Test
    void dropsPaymentOnceItSucceeds() {
        // given — UNKNOWN 하나가 PG 조회 결과로 SUCCESS가 됐다
        Payment payment = unknown(1L);
        payment.applyObservation(successOf(payment), NOW);
        paymentJpaRepository.save(payment);

        // when
        double unknownCount = gauge("UNKNOWN").value();

        // then
        assertThat(unknownCount).isZero();
    }

    @DisplayName("지표의 status label은 PENDING·UNKNOWN 두 값뿐이다 (주문·사용자 식별자를 label로 두지 않는다).")
    @Test
    void hasOnlyTwoStatusSeries() {
        // given
        unknown(1L);
        pending(2L);

        // when
        var gauges = meterRegistry.find(PaymentUnresolvedMetrics.NAME).gauges();

        // then
        assertThat(gauges).extracting(found -> found.getId().getTag("status")).containsExactlyInAnyOrder("PENDING", "UNKNOWN");
        assertThat(gauges).allSatisfy(found -> assertThat(found.getId().getTag("orderId")).isNull());
    }

    private Gauge gauge(String status) {
        return meterRegistry.get(PaymentUnresolvedMetrics.NAME).tag("status", status).gauge();
    }

    private Payment requested(Long orderId) {
        return paymentJpaRepository.save(Payment.request(orderId, 1L, "attempt-" + orderId, "LOCAL_PG", Money.of(AMOUNT), NOW));
    }

    private Payment unknown(Long orderId) {
        Payment payment = Payment.request(orderId, 1L, "attempt-" + orderId, "LOCAL_PG", Money.of(AMOUNT), NOW);
        payment.markSending(NOW);
        payment.recordUnknown(NOW);
        return paymentJpaRepository.save(payment);
    }

    private Payment pending(Long orderId) {
        Payment payment = Payment.request(orderId, 1L, "attempt-" + orderId, "LOCAL_PG", Money.of(AMOUNT), NOW);
        payment.markSending(NOW);
        payment.recordAccepted("20261125:TR:" + orderId, NOW);
        return paymentJpaRepository.save(payment);
    }

    private Payment succeeded(Long orderId) {
        Payment payment = pending(orderId);
        payment.applyObservation(successOf(payment), NOW);
        return paymentJpaRepository.save(payment);
    }

    private static PaymentObservation successOf(Payment payment) {
        String transactionKey = payment.getTransactionKey() != null ? payment.getTransactionKey() : "20261125:TR:" + payment.getOrderId();
        return new PaymentObservation(transactionKey, payment.getProviderOrderId(), Money.of(AMOUNT), ExternalPaymentStatus.SUCCESS, null);
    }
}
