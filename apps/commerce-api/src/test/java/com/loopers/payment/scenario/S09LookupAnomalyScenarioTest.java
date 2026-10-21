package com.loopers.payment.scenario;

import com.loopers.order.domain.OrderStatus;
import com.loopers.payment.application.port.out.PaymentGatewayPort;
import com.loopers.payment.application.port.out.PgPaymentRequest;
import com.loopers.payment.application.port.out.PgTransaction;
import com.loopers.payment.domain.Payment;
import com.loopers.payment.domain.PaymentApplyOutcome;
import com.loopers.payment.domain.PaymentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S-9 조회 0건·복수·불일치 (plan.md 10장, 3-2·3-3, C-6·C-7): 임의 종결·재결제 없음, 이상 기록(last_check_result).
 */
@DisplayName("S-9 조회 0건·복수·불일치")
class S09LookupAnomalyScenarioTest extends PaymentScenarioSupport {

    @Autowired
    private PaymentGatewayPort paymentGatewayPort;

    private void assertUntouched(Long orderId) {
        assertThat(paymentOf(orderId).getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(orderStatusOf(orderId)).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(reservationsOf(orderId)).containsExactly("RESERVED");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payments", Long.class)).isEqualTo(1);
    }

    @DisplayName("S-9 PG가 거래를 만들지 않고 500으로 답해(UNKNOWN) 조회가 0건이면, 고객 확인·스케줄러 모두 결제 UNKNOWN·NOT_FOUND 기록만 남기고 재고 3·create 1 그대로다.")
    @Test
    void zeroTransactionsAreRecordedOnly() throws Exception {
        // arrange
        PG.failNextCreateWithoutCreating();
        Long orderId = confirmAndAwait(2, "attempt-1", PaymentStatus.UNKNOWN);

        // act
        reconcile(orderId)
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.paymentStatus").value("UNKNOWN"));
        paymentRecoveryUseCase.reconcileDue(ZonedDateTime.now().plusMinutes(10));

        // assert
        assertUntouched(orderId);
        assertThat(paymentOf(orderId).getLastCheckResult()).isEqualTo("NOT_FOUND");
        assertThat(paymentOf(orderId).getCheckCount()).isEqualTo(2);
        assertThat(stockOf(airMax)).isEqualTo(3);
        assertThat(PG.transactionsCreated()).isZero();
        assertThat(PG.createCalls()).isEqualTo(1);
    }

    @DisplayName("S-9 같은 PG 주문 식별자로 거래가 2건이면(하나는 SUCCESS여도) 고르지 않고 MULTIPLE:2만 기록한다 — 결제 UNKNOWN·재고 3·reconcile이 create를 늘리지 않는다.")
    @Test
    void multipleTransactionsAreRecordedOnly() throws Exception {
        // arrange: 응답 유실로 UNKNOWN이 된 뒤, PG 쪽에 같은 주문 식별자의 거래가 하나 더 있다
        PG.delayNextCreateResponse(Duration.ofSeconds(1));
        Long orderId = confirmAndAwait(2, "attempt-1", PaymentStatus.UNKNOWN);
        String providerOrderId = paymentOf(orderId).getProviderOrderId();
        PgTransaction second = paymentGatewayPort.request(new PgPaymentRequest(providerOrderId, "SAMSUNG", CARD_NO, 2_000L));
        PG.complete(second.transactionKey(), "SUCCESS", null);
        int createCalls = PG.createCalls();

        // act
        reconcile(orderId)
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.paymentStatus").value("UNKNOWN"));

        // assert
        assertUntouched(orderId);
        assertThat(paymentOf(orderId).getLastCheckResult()).isEqualTo("MULTIPLE:2");
        assertThat(paymentOf(orderId).getTransactionKey()).isNull();
        assertThat(stockOf(airMax)).isEqualTo(3);
        assertThat(PG.createCalls()).isEqualTo(createCalls);
    }

    @DisplayName("S-9 PG가 찾아 준 거래의 금액(1,000)이 저장 금액(2,000)과 다르면 SUCCESS여도 반영하지 않고 MISMATCH:amount를 기록한다 — 결제 UNKNOWN·재고 3.")
    @Test
    void amountMismatchIsRecordedOnly() throws Exception {
        // arrange
        PG.failNextCreateWithoutCreating();
        Long orderId = confirmAndAwait(2, "attempt-1", PaymentStatus.UNKNOWN);
        String providerOrderId = paymentOf(orderId).getProviderOrderId();
        PgTransaction wrongAmount = paymentGatewayPort.request(new PgPaymentRequest(providerOrderId, "SAMSUNG", CARD_NO, 1_000L));
        PG.complete(wrongAmount.transactionKey(), "SUCCESS", null);

        // act
        reconcile(orderId)
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.paymentStatus").value("UNKNOWN"));

        // assert
        assertUntouched(orderId);
        assertThat(paymentOf(orderId).getLastCheckResult()).isEqualTo("MISMATCH:amount");
        assertThat(stockOf(airMax)).isEqualTo(3);
    }

    @DisplayName("S-9 다른 주문의 거래(PG 주문 식별자 불일치)가 결과 적용 경계로 들어오면 MISMATCH:providerOrderId만 기록하고 두 주문 모두 그대로다 — 재고 5→3→2 유지.")
    @Test
    void orderMismatchIsRecordedOnly() throws Exception {
        // arrange: 두 주문이 모두 PG 수락(PENDING) 상태이고, B는 PG에서 승인됐다
        Long orderA = confirmAndAwait(2, "attempt-a", PaymentStatus.PENDING);
        Long orderB = confirmAndAwait(1, "attempt-b", PaymentStatus.PENDING);
        PG.complete(paymentOf(orderB).getTransactionKey(), "SUCCESS", null);
        PgTransaction transactionOfB = paymentGatewayPort.findByTransactionKey(paymentOf(orderB).getTransactionKey()).orElseThrow();

        // act: B의 거래가 A의 결과로 잘못 들어온다
        PaymentApplyOutcome outcome = paymentResultApplier.apply(paymentOf(orderA).getId(), transactionOfB);

        // assert
        assertThat(outcome).isEqualTo(PaymentApplyOutcome.MISMATCH);
        Payment paymentA = paymentOf(orderA);
        assertThat(paymentA.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(paymentA.getLastCheckResult()).isEqualTo("MISMATCH:providerOrderId");
        assertThat(orderStatusOf(orderA)).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(paymentOf(orderB).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(orderStatusOf(orderB)).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(reservationsOf(orderA)).containsExactly("RESERVED");
        assertThat(stockOf(airMax)).isEqualTo(2);
        assertThat(PG.createCalls()).isEqualTo(2);
    }
}
