package com.loopers.payment.scenario;

import com.loopers.order.domain.OrderStatus;
import com.loopers.payment.application.port.in.PaymentRecoveryUseCase.StaleDispatchResult;
import com.loopers.payment.application.port.out.PaymentOutboxPort;
import com.loopers.payment.application.port.out.PaymentPort;
import com.loopers.payment.domain.Payment;
import com.loopers.payment.domain.PaymentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S-8 발송 기록 뒤 재시작 (plan.md 10장, 5-1, ADR-W5-01·03, C-6): 시도·예약 유지, 자동 재발송 0.
 * 재시작 뒤에는 카드 번호(메모리에만 있음)가 없어 다시 보낼 수도 없다. 정리 스케줄러는 보내지 않은 것만 실패로 닫고, 보냈을 수 있는 것은 UNKNOWN → 조회로 넘긴다.
 */
@DisplayName("S-8 발송 기록 뒤 재시작")
class S08RestartAfterDispatchScenarioTest extends PaymentScenarioSupport {

    @Autowired
    private PaymentOutboxPort paymentOutboxPort;

    @Autowired
    private PaymentPort paymentPort;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @DisplayName("S-8 commit 뒤 발송 이벤트를 잃으면(장부 PENDING) 결제 REQUESTED·예약 RESERVED·재고 3이 남고, 30초 전에는 그대로, 30초 뒤 정리가 FAILED(NOT_SENT)·재고 5로 닫는다. PG create는 끝까지 0번이다.")
    @Test
    void lostEventIsClosedWithoutSending() throws Exception {
        // arrange: TX A는 commit됐지만 발송 이벤트가 사라진다 (commit 직후 프로세스 종료)
        doNothing().when(paymentDispatchUseCase).dispatch(any());
        Long orderId = createOrder(2);
        confirm(orderId, user, "attempt-1")
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.paymentStatus").value("REQUESTED"));
        assertThat(outboxStatusOf(orderId)).isEqualTo("PENDING");
        assertThat(reservationsOf(orderId)).containsExactly("RESERVED");
        assertThat(stockOf(airMax)).isEqualTo(3);

        // act & assert: 재시작 뒤 30초가 안 됐으면 정리도 reconcile도 손대지 않는다
        StaleDispatchResult early = paymentRecoveryUseCase.closeStaleDispatches(ZonedDateTime.now().plusSeconds(20));
        int checked = paymentRecoveryUseCase.reconcileDue(ZonedDateTime.now().plusMinutes(10));
        assertThat(early.closedNotSent()).isZero();
        assertThat(checked).isZero();
        assertThat(paymentOf(orderId).getStatus()).isEqualTo(PaymentStatus.REQUESTED);
        assertThat(stockOf(airMax)).isEqualTo(3);

        // act: 30초가 지나 정리 스케줄러가 돈다
        StaleDispatchResult closed = paymentRecoveryUseCase.closeStaleDispatches(ZonedDateTime.now().plusSeconds(31));

        // assert
        assertThat(closed.closedNotSent()).isEqualTo(1);
        Payment failed = paymentOf(orderId);
        assertThat(failed.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(failed.getFailureReason()).isEqualTo(Payment.NOT_SENT);
        assertThat(outboxStatusOf(orderId)).isEqualTo("CLOSED");
        assertThat(orderStatusOf(orderId)).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(reservationsOf(orderId)).containsExactly("RELEASED");
        assertThat(stockOf(airMax)).isEqualTo(5);
        // 같은 키로 다시 눌러도 같은 (실패한) 시도를 돌려받을 뿐 보내지 않는다
        confirm(orderId, user, "attempt-1")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.paymentStatus").value("FAILED"));
        assertThat(PG.createCalls()).isZero();
    }

    @DisplayName("S-8 PG가 거래를 만든 뒤 결과를 기록하기 전에 프로세스가 죽으면(결제 SENDING·장부 CLAIMED) 정리가 UNKNOWN으로만 넘기고, reconcile이 그 거래를 찾아 확정한다. 예약은 확정까지 유지되고 create는 1번이다.")
    @Test
    void diedAfterSendingIsReconciledWithoutResend() throws Exception {
        // arrange: 생성 응답을 기록하려는 순간 프로세스가 멈춘다
        doThrow(new IllegalStateException("프로세스 중단")).when(paymentResultApplier).recordCreated(anyLong(), any());
        Long orderId = createOrder(2);
        confirm(orderId, user, "attempt-1");
        verify(paymentResultApplier, timeout(WAIT.toMillis())).recordCreated(anyLong(), any());
        assertThat(paymentOf(orderId).getStatus()).isEqualTo(PaymentStatus.SENDING);
        assertThat(outboxStatusOf(orderId)).isEqualTo("CLAIMED");
        assertThat(PG.transactionsCreated()).isEqualTo(1);
        assertThat(reservationsOf(orderId)).containsExactly("RESERVED");

        // act: 재시작 뒤 30초가 지나 정리 스케줄러가 돈다
        StaleDispatchResult stale = paymentRecoveryUseCase.closeStaleDispatches(ZonedDateTime.now().plusSeconds(31));

        // assert: 보냈을 수 있으니 실패로 닫지 않고 UNKNOWN으로만 넘긴다
        assertThat(stale.closedNotSent()).isZero();
        assertThat(stale.markedUnknown()).isEqualTo(1);
        assertThat(paymentOf(orderId).getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(outboxStatusOf(orderId)).isEqualTo("DONE");
        assertThat(reservationsOf(orderId)).containsExactly("RESERVED");
        assertThat(stockOf(airMax)).isEqualTo(3);

        // act: reconcile 스케줄러가 PG 주문 식별자로 거래를 찾고, PG 승인 뒤 callback으로 확정된다
        paymentRecoveryUseCase.reconcileDue(ZonedDateTime.now().plusMinutes(10));
        Payment pending = paymentOf(orderId);
        assertThat(pending.getStatus()).isEqualTo(PaymentStatus.PENDING);
        PG.complete(pending.getTransactionKey(), "SUCCESS", null);
        int callback = PG.sendCallback(pending.getTransactionKey());

        // assert
        assertThat(callback).isEqualTo(200);
        assertThat(paymentOf(orderId).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(orderStatusOf(orderId)).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(reservationsOf(orderId)).containsExactly("CONSUMED");
        assertThat(stockOf(airMax)).isEqualTo(3);
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(PG.transactionsCreated()).isEqualTo(1);
    }

    @DisplayName("S-8 발송 권한만 얻고 PG에 닿기 전에 죽으면 UNKNOWN이 되고, 조회 0건이어도 실패로 닫거나 다시 보내지 않는다 — 결제 UNKNOWN·예약 RESERVED·재고 3·create 0이 몇 번의 reconcile 뒤에도 그대로다.")
    @Test
    void diedBeforeSendingIsNeverResent() throws Exception {
        // arrange: 이벤트를 잃은 결제를, 죽기 직전 실행이 발송 권한까지 얻은 상태로 만든다
        doNothing().when(paymentDispatchUseCase).dispatch(any());
        Long orderId = createOrder(2);
        confirm(orderId, user, "attempt-1").andExpect(status().isAccepted());
        Long paymentId = paymentOf(orderId).getId();
        transactionTemplate.executeWithoutResult(status -> {
            paymentOutboxPort.claim(paymentId, ZonedDateTime.now());
            Payment payment = paymentPort.findByIdForUpdate(paymentId).orElseThrow();
            payment.markSending(ZonedDateTime.now());
            paymentPort.save(payment);
        });

        // act: 재시작 뒤 정리, 그리고 reconcile을 여러 주기 돌린다
        StaleDispatchResult stale = paymentRecoveryUseCase.closeStaleDispatches(ZonedDateTime.now().plusSeconds(31));
        paymentRecoveryUseCase.reconcileDue(ZonedDateTime.now().plusMinutes(10));
        paymentRecoveryUseCase.reconcileDue(ZonedDateTime.now().plus(Duration.ofHours(1)));

        // assert
        assertThat(stale.markedUnknown()).isEqualTo(1);
        Payment unknown = paymentOf(orderId);
        assertThat(unknown.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(unknown.getLastCheckResult()).isEqualTo("NOT_FOUND");
        assertThat(unknown.getCheckCount()).isEqualTo(2);
        assertThat(orderStatusOf(orderId)).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(reservationsOf(orderId)).containsExactly("RESERVED");
        assertThat(stockOf(airMax)).isEqualTo(3);
        assertThat(PG.createCalls()).isZero();
    }
}
