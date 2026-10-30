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
 * S-3 효과 전 확정 거절 (plan.md 10장, 3-3, C-5): 외부 거래 0, 주문 실패, 예약 반환 1.
 */
@DisplayName("S-3 효과 전 확정 거절")
class S03RejectedBeforeEffectScenarioTest extends PaymentScenarioSupport {

    @DisplayName("S-3 PG가 거래를 만들지 않고 400으로 거절하면 외부 거래 0·결제 FAILED·주문 PAYMENT_FAILED·예약 RELEASED 1이고 재고가 5→3→5로 한 번만 돌아온다. 정리·reconcile을 돌려도 7이 되지 않는다.")
    @Test
    void releasesReservationOnce() throws Exception {
        // arrange
        PG.rejectNextCreate();
        Long orderId = createOrder(2);

        // act
        confirm(orderId, user, "attempt-1");
        awaitPayment(orderId, PaymentStatus.FAILED);

        // assert
        getPayment(orderId)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.paymentStatus").value("FAILED"))
            .andExpect(jsonPath("$.data.orderStatus").value("PAYMENT_FAILED"));
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(PG.transactionsCreated()).isZero();
        assertThat(paymentOf(orderId).getTransactionKey()).isNull();
        assertThat(orderStatusOf(orderId)).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(reservationsOf(orderId)).containsExactly("RELEASED");
        assertThat(stockOf(airMax)).isEqualTo(5);

        // act & assert: 스케줄러가 다시 돌아도 반환은 한 번뿐이고 다시 보내지 않는다
        paymentRecoveryUseCase.closeStaleDispatches(ZonedDateTime.now().plusMinutes(1));
        paymentRecoveryUseCase.reconcileDue(ZonedDateTime.now().plusMinutes(10));
        reconcile(orderId).andExpect(status().isOk());
        assertThat(reservationsOf(orderId)).containsExactly("RELEASED");
        assertThat(stockOf(airMax)).isEqualTo(5);
        assertThat(PG.createCalls()).isEqualTo(1);
    }
}
