package com.loopers.payment.scenario;

import com.loopers.order.domain.OrderStatus;
import com.loopers.payment.application.port.in.PaymentReconcileUseCase;
import com.loopers.payment.domain.Payment;
import com.loopers.payment.domain.PaymentStatus;
import com.loopers.product.domain.ProductModel;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S-10 timeout·회로 OPEN·복구 (plan.md 10장, 6장, ADR-W5-03, C-8): 호출 수 제한, 상태 유지, 회복 뒤 조회 재개.
 * 회로 설정은 운영과 같다(최근 10건·최소 5건·실패율 50%). OPEN 대기만 2s로 줄였다 (PaymentScenarioSupport).
 */
@DisplayName("S-10 timeout·회로 OPEN·복구")
class S10TimeoutCircuitRecoveryScenarioTest extends PaymentScenarioSupport {

    private static final int SLOW_CALLS = 5;

    @Autowired
    private PaymentReconcileUseCase paymentReconcileUseCase;

    @DisplayName("S-10 PG 생성이 5번 읽기 timeout(UNKNOWN, 거래 5)이면 생성 회로가 OPEN되고, 다음 확정은 PG에 보내지 않고 FAILED(NOT_SENT)·재고 반환으로 끝난다(create 5 그대로). UNKNOWN 5건은 실패로 닫지 않고, 회로가 HALF_OPEN이 되면 생성과 조회가 다시 된다.")
    @Test
    void createCircuitOpensAndFailsFastThenRecovers() throws Exception {
        // arrange
        ProductModel jordan = productJpaRepository.save(new ProductModel(nike.getId(), "조던", 1_000, 10));
        List<Long> slowOrders = new ArrayList<>();
        for (int i = 0; i < SLOW_CALLS; i++) {
            PG.delayNextCreateResponse(Duration.ofSeconds(1));
            Long orderId = createOrder(user, jordan, 1);
            confirm(orderId, user, "slow-" + i).andExpect(status().isAccepted());
            slowOrders.add(orderId);
        }
        slowOrders.forEach(orderId -> awaitPayment(orderId, PaymentStatus.UNKNOWN));
        assertThat(circuit(CREATE_CIRCUIT).getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(PG.createCalls()).isEqualTo(SLOW_CALLS);
        assertThat(PG.transactionsCreated()).isEqualTo(SLOW_CALLS);
        assertThat(stockOf(jordan)).isEqualTo(5);

        // act: 회로가 열린 동안의 확정
        Long blocked = createOrder(user, jordan, 1);
        confirm(blocked, user, "blocked");
        awaitPayment(blocked, PaymentStatus.FAILED);

        // assert: 보내지 않았다는 게 확실하니 바로 실패로 닫고 재고를 돌려준다
        assertThat(paymentOf(blocked).getFailureReason()).isEqualTo(Payment.NOT_SENT);
        assertThat(orderStatusOf(blocked)).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(reservationsOf(blocked)).containsExactly("RELEASED");
        assertThat(stockOf(jordan)).isEqualTo(5);
        assertThat(PG.createCalls()).isEqualTo(SLOW_CALLS);
        // 보냈을 수 있는 UNKNOWN은 회로와 무관하게 그대로다
        slowOrders.forEach(orderId -> {
            assertThat(paymentOf(orderId).getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
            assertThat(reservationsOf(orderId)).containsExactly("RESERVED");
        });

        // act: OPEN 대기가 지나 HALF_OPEN이 된다 (운영 10s를 기다리지 않고 회로를 옮긴다)
        circuit(CREATE_CIRCUIT).transitionToHalfOpenState();
        Long recovered = createOrder(user, jordan, 1);
        confirm(recovered, user, "recovered").andExpect(status().isAccepted());
        awaitPayment(recovered, PaymentStatus.PENDING);
        int checked = paymentRecoveryUseCase.reconcileDue(ZonedDateTime.now().plusMinutes(10));

        // assert: 생성이 다시 나가고, UNKNOWN 5건은 조회로 거래 키를 찾아 PENDING이 된다 (새 생성 없음)
        assertThat(PG.createCalls()).isEqualTo(SLOW_CALLS + 1);
        assertThat(checked).isEqualTo(SLOW_CALLS + 1);
        slowOrders.forEach(orderId -> assertThat(paymentOf(orderId).getStatus()).isEqualTo(PaymentStatus.PENDING));
        assertThat(PG.transactionsCreated()).isEqualTo(SLOW_CALLS + 1);
        assertThat(stockOf(jordan)).isEqualTo(4);
    }

    @DisplayName("S-10 PG 조회가 timeout(3번)·500(2번)으로 실패하면 조회 회로가 OPEN되어 다음 확인은 PG를 부르지 않고(lookup 5 그대로) UNAVAILABLE만 기록한다 — 결제 PENDING·재고 3. OPEN 대기(2s)가 지나면 조회가 재개되어 SUCCESS를 반영하고 회로가 CLOSED로 돌아온다.")
    @Test
    void lookupCircuitOpensKeepsStateThenRecovers() throws Exception {
        // arrange
        Long orderId = confirmAndAwait(2, "attempt-1", PaymentStatus.PENDING);
        String transactionKey = paymentOf(orderId).getTransactionKey();
        PG.delayNextLookups(3, Duration.ofSeconds(1));
        PG.failNextLookups(2);

        // act & assert: 재시도 3번이 모두 timeout
        reconcile(orderId)
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.paymentStatus").value("PENDING"));
        assertThat(PG.lookupCalls()).isEqualTo(3);
        assertThat(paymentOf(orderId).getLastCheckResult()).isEqualTo("UNAVAILABLE");

        // act & assert: 500 두 번으로 최소 5건을 채워 회로가 열린다
        reconcile(orderId).andExpect(status().isAccepted());
        assertThat(PG.lookupCalls()).isEqualTo(5);
        assertThat(circuit(LOOKUP_CIRCUIT).getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // act & assert: 열린 동안에는 PG를 부르지 않고 상태를 유지한다
        PG.complete(transactionKey, "SUCCESS", null);
        reconcile(orderId)
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.paymentStatus").value("PENDING"));
        assertThat(PG.lookupCalls()).isEqualTo(5);
        Payment kept = paymentOf(orderId);
        assertThat(kept.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(kept.getLastCheckResult()).isEqualTo("UNAVAILABLE");
        assertThat(kept.getCheckCount()).isEqualTo(3);
        assertThat(orderStatusOf(orderId)).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(reservationsOf(orderId)).containsExactly("RESERVED");
        assertThat(stockOf(airMax)).isEqualTo(3);

        // act: OPEN 대기가 지날 때까지 고객이 확인을 반복한다
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(200))
            .until(() -> paymentReconcileUseCase.reconcile(user.getId(), orderId).paymentStatus() == PaymentStatus.SUCCESS);
        paymentReconcileUseCase.reconcile(user.getId(), orderId);

        // assert
        assertThat(orderStatusOf(orderId)).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(reservationsOf(orderId)).containsExactly("CONSUMED");
        assertThat(stockOf(airMax)).isEqualTo(3);
        assertThat(circuit(LOOKUP_CIRCUIT).getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(PG.createCalls()).isEqualTo(1);
    }
}
