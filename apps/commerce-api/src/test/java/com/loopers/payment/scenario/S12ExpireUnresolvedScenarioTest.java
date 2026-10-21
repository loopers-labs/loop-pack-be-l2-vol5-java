package com.loopers.payment.scenario;

import com.loopers.order.domain.OrderStatus;
import com.loopers.payment.domain.Payment;
import com.loopers.payment.domain.PaymentStatus;
import com.loopers.support.concurrency.ConcurrentRunner;
import com.loopers.support.concurrency.ConcurrentRunner.Kind;
import com.loopers.support.concurrency.ConcurrentRunner.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S-12 보낸 뒤 30분 미해결 결제의 만료 (P-14·P-15): 마지막 조회로도 종결이 아니면 FAILED(EXPIRED)로 닫고 재고를 돌려준다.
 * 만료 뒤 PG 성공이 오면 CONFLICT로 남기고 재고는 다시 건드리지 않는다 (수동 환불 대상).
 * 30분을 기다리지 않고 "지금"을 앞당겨 넘긴다 (S-8과 같은 방식).
 */
@DisplayName("S-12 보낸 뒤 30분 미해결 결제의 만료")
class S12ExpireUnresolvedScenarioTest extends PaymentScenarioSupport {

    private static ZonedDateTime minutesLater(int minutes) {
        return ZonedDateTime.now().plusMinutes(minutes);
    }

    @DisplayName("S-12 PG가 거래를 만들지 않아(UNKNOWN) 조회가 계속 0건이면, 31분 뒤 만료가 마지막으로 한 번 더 조회하고 FAILED(EXPIRED)·PAYMENT_FAILED·RELEASED·재고 5로 닫는다. create는 1번 그대로다.")
    @Test
    void unknownWithoutTransactionIsExpired() throws Exception {
        // arrange
        PG.failNextCreateWithoutCreating();
        Long orderId = confirmAndAwait(2, "attempt-1", PaymentStatus.UNKNOWN);
        int lookupsBefore = PG.lookupCalls();

        // act
        int expired = paymentRecoveryUseCase.expireOverdue(minutesLater(31));

        // assert
        assertThat(expired).isEqualTo(1);
        Payment payment = paymentOf(orderId);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureReason()).isEqualTo("EXPIRED");
        assertThat(payment.getLastCheckResult()).isEqualTo("NOT_FOUND");
        assertThat(orderStatusOf(orderId)).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(reservationsOf(orderId)).containsExactly("RELEASED");
        assertThat(stockOf(airMax)).isEqualTo(5);
        assertThat(PG.lookupCalls()).isEqualTo(lookupsBefore + 1);
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(PG.transactionsCreated()).isZero();
    }

    @DisplayName("S-12 같은 UNKNOWN이라도 보낸 지 29분이면 만료하지 않는다 — UNKNOWN·RESERVED·재고 3, 조회도 하지 않는다.")
    @Test
    void unknownBefore30MinutesIsKept() throws Exception {
        // arrange
        PG.failNextCreateWithoutCreating();
        Long orderId = confirmAndAwait(2, "attempt-1", PaymentStatus.UNKNOWN);
        int lookupsBefore = PG.lookupCalls();

        // act
        int expired = paymentRecoveryUseCase.expireOverdue(minutesLater(29));

        // assert
        assertThat(expired).isZero();
        assertThat(paymentOf(orderId).getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(orderStatusOf(orderId)).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(reservationsOf(orderId)).containsExactly("RESERVED");
        assertThat(stockOf(airMax)).isEqualTo(3);
        assertThat(PG.lookupCalls()).isEqualTo(lookupsBefore);
    }

    @DisplayName("S-12 PENDING이 만료 직전 마지막 조회에서 SUCCESS면 만료하지 않고 확정한다 — CONFIRMED·CONSUMED·재고 3.")
    @Test
    void pendingThatSucceededIsConfirmedNotExpired() throws Exception {
        // arrange: PG는 승인했지만 callback이 오지 않았다
        Long orderId = confirmAndAwait(2, "attempt-1", PaymentStatus.PENDING);
        PG.complete(paymentOf(orderId).getTransactionKey(), "SUCCESS", null);

        // act
        int expired = paymentRecoveryUseCase.expireOverdue(minutesLater(31));

        // assert
        assertThat(expired).isZero();
        assertThat(paymentOf(orderId).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(orderStatusOf(orderId)).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(reservationsOf(orderId)).containsExactly("CONSUMED");
        assertThat(stockOf(airMax)).isEqualTo(3);
        assertThat(PG.createCalls()).isEqualTo(1);
    }

    @DisplayName("S-12 PG가 30분 넘게 처리 중이라 만료로 닫은 뒤 PG가 승인 callback을 보내면, 200으로 받되 결제는 FAILED·CONFLICT 기록, 주문 PAYMENT_FAILED, 재고 5 그대로다(7도 3도 아님).")
    @Test
    void successAfterExpiryIsConflict() throws Exception {
        // arrange
        Long orderId = confirmAndAwait(2, "attempt-1", PaymentStatus.PENDING);
        String transactionKey = paymentOf(orderId).getTransactionKey();
        int expired = paymentRecoveryUseCase.expireOverdue(minutesLater(31));
        assertThat(expired).isEqualTo(1);
        assertThat(stockOf(airMax)).isEqualTo(5);

        // act: 그 뒤 PG가 승인하고 callback을 보낸다
        PG.complete(transactionKey, "SUCCESS", null);
        int callback = PG.sendCallback(transactionKey);

        // assert
        assertThat(callback).isEqualTo(200);
        Payment payment = paymentOf(orderId);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureReason()).isEqualTo("EXPIRED");
        assertThat(payment.getLastCheckResult()).isEqualTo("CONFLICT:FAILED<-SUCCESS");
        assertThat(orderStatusOf(orderId)).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(reservationsOf(orderId)).containsExactly("RELEASED");
        assertThat(stockOf(airMax)).isEqualTo(5);
    }

    @DisplayName("S-12 만료 두 번과 callback이 동시에 와도 만료는 1건이고 재고는 한 번만 돌아온다 — FAILED(EXPIRED)·RELEASED·재고 5.")
    @Test
    void concurrentExpiryAndCallbackReleaseOnce() throws Exception {
        // arrange: PG는 계속 처리 중이다
        Long orderId = confirmAndAwait(2, "attempt-1", PaymentStatus.PENDING);
        String transactionKey = paymentOf(orderId).getTransactionKey();
        ZonedDateTime now = minutesLater(31);
        AtomicInteger expired = new AtomicInteger();
        List<Callable<?>> tasks = List.of(
            () -> expired.addAndGet(paymentRecoveryUseCase.expireOverdue(now)),
            () -> expired.addAndGet(paymentRecoveryUseCase.expireOverdue(now)),
            () -> PG.sendCallback(transactionKey)
        );

        // act
        List<Outcome> outcomes = ConcurrentRunner.run(tasks);

        // assert
        assertThat(ConcurrentRunner.count(outcomes, Kind.SUCCESS)).isEqualTo(3);
        assertThat(expired.get()).isEqualTo(1);
        Payment payment = paymentOf(orderId);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureReason()).isEqualTo("EXPIRED");
        assertThat(orderStatusOf(orderId)).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(reservationsOf(orderId)).containsExactly("RELEASED");
        assertThat(stockOf(airMax)).isEqualTo(5);
    }
}
