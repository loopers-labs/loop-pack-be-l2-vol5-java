package com.loopers.payment.scenario;

import com.loopers.order.domain.OrderStatus;
import com.loopers.payment.domain.PaymentStatus;
import com.loopers.product.application.port.in.StockReservationUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.AopTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

/**
 * S-7 외부 성공 뒤 내부 저장 실패 (plan.md 10장, 4장 결과 적용 5번, R-5, C-8): rollback, 재조회로 한 번 확정, create 추가 0.
 * 결과 적용 중 예약 소비 저장이 한 번 실패하도록 spy에 넣는다.
 */
@DisplayName("S-7 외부 성공 뒤 내부 저장 실패")
class S07InternalSaveFailureScenarioTest extends PaymentScenarioSupport {

    @DisplayName("S-7 PG SUCCESS callback을 반영하다 예약 저장이 실패하면 callback은 500이고 결제·주문·예약이 모두 rollback(PENDING·PAYMENT_PENDING·RESERVED, 재고 3)되며, PG가 callback을 다시 보내면 한 번만 확정되고 create는 1번이다.")
    @Test
    void rollsBackThenAppliesOnceOnRedelivery() throws Exception {
        // arrange
        Long orderId = confirmAndAwait(2, "attempt-1", PaymentStatus.PENDING);
        String transactionKey = paymentOf(orderId).getTransactionKey();
        PG.complete(transactionKey, "SUCCESS", null);
        // 트랜잭션 프록시(MANDATORY) 안쪽의 spy에 정한다 — 첫 소비만 실패하고 그 뒤는 실제 동작이다
        StockReservationUseCase target = AopTestUtils.getUltimateTargetObject(stockReservationUseCase);
        doThrow(new IllegalStateException("예약 저장 실패")).doCallRealMethod().when(target).consume(anyLong());

        // act: 첫 callback은 반영 도중 실패한다
        int failedCallback = PG.sendCallback(transactionKey);

        // assert: 외부는 성공했지만 내부는 아무것도 남기지 않았다
        assertThat(failedCallback).isEqualTo(500);
        assertThat(paymentOf(orderId).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(orderStatusOf(orderId)).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(reservationsOf(orderId)).containsExactly("RESERVED");
        assertThat(stockOf(airMax)).isEqualTo(3);

        // act: PG가 callback을 다시 보낸다 (권위 조회로 같은 외부 결과를 다시 읽는다)
        int redelivered = PG.sendCallback(transactionKey);
        int duplicate = PG.sendCallback(transactionKey);

        // assert
        assertThat(redelivered).isEqualTo(200);
        assertThat(duplicate).isEqualTo(200);
        assertThat(paymentOf(orderId).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(orderStatusOf(orderId)).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(reservationsOf(orderId)).containsExactly("CONSUMED");
        assertThat(stockOf(airMax)).isEqualTo(3);
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(PG.transactionsCreated()).isEqualTo(1);
    }
}
