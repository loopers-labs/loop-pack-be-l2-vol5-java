package com.loopers.payment.scenario;

import com.loopers.order.domain.OrderStatus;
import com.loopers.payment.domain.PaymentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S-2 생성 수락 PENDING (plan.md 10장, 3-3): 주문 미확정, 예약 유지, 조회 SUCCESS 뒤에만 확정.
 */
@DisplayName("S-2 생성 수락 PENDING")
class S02AcceptedPendingScenarioTest extends PaymentScenarioSupport {

    @DisplayName("S-2 PG가 생성을 수락(PENDING)하면 주문은 PAYMENT_PENDING·예약 RESERVED·재고 3이고, 조회가 PENDING인 동안은 그대로이며, 조회가 SUCCESS가 된 뒤에만 CONFIRMED·CONSUMED가 된다.")
    @Test
    void confirmsOnlyAfterLookupSuccess() throws Exception {
        // arrange
        Long orderId = confirmAndAwait(2, "attempt-1", PaymentStatus.PENDING);
        String transactionKey = paymentOf(orderId).getTransactionKey();

        // act & assert: 수락만 된 상태
        getPayment(orderId)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.paymentStatus").value("PENDING"))
            .andExpect(jsonPath("$.data.orderStatus").value("PAYMENT_PENDING"));
        assertThat(reservationsOf(orderId)).containsExactly("RESERVED");
        assertThat(stockOf(airMax)).isEqualTo(3);

        // act & assert: 고객 확인·스케줄러 확인 모두 PG가 아직 PENDING이면 아무것도 바꾸지 않는다
        reconcile(orderId)
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.paymentStatus").value("PENDING"));
        paymentRecoveryUseCase.reconcileDue(ZonedDateTime.now().plusMinutes(10));
        assertThat(paymentOf(orderId).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(orderStatusOf(orderId)).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(reservationsOf(orderId)).containsExactly("RESERVED");
        assertThat(stockOf(airMax)).isEqualTo(3);

        // act & assert: PG가 승인한 뒤의 조회로 확정된다
        PG.complete(transactionKey, "SUCCESS", null);
        reconcile(orderId)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.paymentStatus").value("SUCCESS"))
            .andExpect(jsonPath("$.data.orderStatus").value("CONFIRMED"));
        assertThat(reservationsOf(orderId)).containsExactly("CONSUMED");
        assertThat(stockOf(airMax)).isEqualTo(3);
        assertThat(PG.createCalls()).isEqualTo(1);
    }
}
