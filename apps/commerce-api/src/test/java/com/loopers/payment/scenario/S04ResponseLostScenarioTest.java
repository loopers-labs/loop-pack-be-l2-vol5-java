package com.loopers.payment.scenario;

import com.loopers.order.domain.OrderStatus;
import com.loopers.payment.application.port.in.PaymentRecoveryUseCase.StaleDispatchResult;
import com.loopers.payment.domain.Payment;
import com.loopers.payment.domain.PaymentStatus;
import com.loopers.support.pg.LocalTestPgServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S-4 거래 생성 뒤 응답만 유실 (plan.md 10장, 3-2 UNKNOWN, C-6): 거래 1·UNKNOWN, create 추가 0, 조회로 종결.
 * 과제 PG는 멱등 키가 없어 다시 보내면 거래가 둘 생긴다 — 그래서 결과를 모르면 조회만 한다.
 */
@DisplayName("S-4 거래 생성 뒤 응답만 유실")
class S04ResponseLostScenarioTest extends PaymentScenarioSupport {

    @DisplayName("S-4 PG가 거래를 만든 뒤 응답이 읽기 timeout(500ms)보다 늦으면 결제 UNKNOWN·거래 1·예약 유지(재고 3)이고, 정리·reconcile을 돌려도 create는 1번뿐이며, PG 주문 식별자 조회로 SUCCESS를 찾아 확정한다.")
    @Test
    void readTimeoutBecomesUnknownThenLookupConfirms() throws Exception {
        // arrange
        PG.delayNextCreateResponse(Duration.ofSeconds(1));

        // act
        Long orderId = confirmAndAwait(2, "attempt-1", PaymentStatus.UNKNOWN);

        // assert: 보냈을 수 있으니 실패로 닫지 않고 재고도 풀지 않는다
        Payment unknown = paymentOf(orderId);
        assertThat(unknown.getTransactionKey()).isNull();
        assertThat(PG.transactionsCreated()).isEqualTo(1);
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(orderStatusOf(orderId)).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(reservationsOf(orderId)).containsExactly("RESERVED");
        assertThat(stockOf(airMax)).isEqualTo(3);

        // act: 정리 스케줄러는 이미 결과를 기록한(장부 DONE) 결제를 건드리지 않는다
        StaleDispatchResult closed = paymentRecoveryUseCase.closeStaleDispatches(ZonedDateTime.now().plusMinutes(1));
        assertThat(closed.closedNotSent()).isZero();
        assertThat(closed.markedUnknown()).isZero();

        // act: PG가 승인으로 끝낸 뒤 고객이 확인을 누른다 (거래 키가 없으니 PG 주문 식별자로 찾는다)
        List<LocalTestPgServer.Transaction> atPg = PG.transactionsOf(unknown.getProviderOrderId());
        assertThat(atPg).hasSize(1);
        PG.complete(atPg.get(0).transactionKey(), "SUCCESS", null);
        reconcile(orderId)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.paymentStatus").value("SUCCESS"))
            .andExpect(jsonPath("$.data.orderStatus").value("CONFIRMED"))
            .andExpect(jsonPath("$.data.transactionKey").value(atPg.get(0).transactionKey()));
        paymentRecoveryUseCase.reconcileDue(ZonedDateTime.now().plusMinutes(10));

        // assert
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(PG.transactionsCreated()).isEqualTo(1);
        assertThat(reservationsOf(orderId)).containsExactly("CONSUMED");
        assertThat(stockOf(airMax)).isEqualTo(3);
    }

    @DisplayName("S-4 PG가 거래를 만든 뒤 응답 없이 연결을 끊어도 숨은 POST 재전송 없이 create 1·UNKNOWN이고, 스케줄러 reconcile이 거래 1건을 찾아 FAILED를 반영해 재고가 3→5로 한 번 돌아온다.")
    @Test
    void droppedConnectionBecomesUnknownThenSchedulerFails() throws Exception {
        // arrange
        PG.dropNextCreateConnection();

        // act
        Long orderId = confirmAndAwait(2, "attempt-1", PaymentStatus.UNKNOWN);

        // assert
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(PG.transactionsCreated()).isEqualTo(1);
        assertThat(stockOf(airMax)).isEqualTo(3);

        // act: PG가 실패로 끝낸 뒤 reconcile 스케줄러가 돈다
        String providerOrderId = paymentOf(orderId).getProviderOrderId();
        PG.complete(PG.transactionsOf(providerOrderId).get(0).transactionKey(), "FAILED", "한도 초과");
        paymentRecoveryUseCase.closeStaleDispatches(ZonedDateTime.now().plusMinutes(1));
        int checked = paymentRecoveryUseCase.reconcileDue(ZonedDateTime.now().plusMinutes(10));

        // assert
        assertThat(checked).isEqualTo(1);
        Payment failed = paymentOf(orderId);
        assertThat(failed.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(failed.getFailureReason()).isEqualTo("한도 초과");
        assertThat(orderStatusOf(orderId)).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(reservationsOf(orderId)).containsExactly("RELEASED");
        assertThat(stockOf(airMax)).isEqualTo(5);
        assertThat(PG.createCalls()).isEqualTo(1);
    }
}
