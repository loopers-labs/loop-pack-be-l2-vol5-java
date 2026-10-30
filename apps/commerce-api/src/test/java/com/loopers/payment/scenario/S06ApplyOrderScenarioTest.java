package com.loopers.payment.scenario;

import com.loopers.order.domain.OrderStatus;
import com.loopers.payment.application.port.in.PaymentReconcileUseCase;
import com.loopers.payment.domain.PaymentStatus;
import com.loopers.support.concurrency.ConcurrentRunner;
import com.loopers.support.concurrency.ConcurrentRunner.Kind;
import com.loopers.support.concurrency.ConcurrentRunner.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S-6 callback-first·lookup-first·동시 적용 (plan.md 10장, 4-1, ADR-W5-04, C-5·C-8): 같은 최종 상태, 추가 차감·중복 반환 0.
 * callback은 fixture가 실제 HTTP로 보내고, lookup은 고객 확인 API(reconcile)다. 둘 다 PG 권위 조회 → 결과 적용 경계로 간다.
 */
@DisplayName("S-6 callback-first·lookup-first·동시 적용")
class S06ApplyOrderScenarioTest extends PaymentScenarioSupport {

    @Autowired
    private PaymentReconcileUseCase paymentReconcileUseCase;

    @DisplayName("S-6 callback이 먼저 오고(중복 2번) 고객 확인이 뒤에 와도 최종 상태는 같고 반영은 한 번이다:")
    @ParameterizedTest(name = "PG {0} → 주문 {1}, 예약 {2} 1행, 재고 {3}")
    @CsvSource({
        "SUCCESS, CONFIRMED, CONSUMED, 3",
        "FAILED, PAYMENT_FAILED, RELEASED, 5",
    })
    void callbackFirst(String pgStatus, OrderStatus orderStatus, String reservation, int stock) throws Exception {
        // arrange
        Long orderId = confirmAndAwait(2, "attempt-1", PaymentStatus.PENDING);
        String transactionKey = paymentOf(orderId).getTransactionKey();
        PG.complete(transactionKey, pgStatus, null);

        // act
        int first = PG.sendCallback(transactionKey);
        int duplicate = PG.sendCallback(transactionKey);
        reconcile(orderId)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.paymentStatus").value(pgStatus))
            .andExpect(jsonPath("$.data.orderStatus").value(orderStatus.name()));

        // assert
        assertThat(first).isEqualTo(200);
        assertThat(duplicate).isEqualTo(200);
        assertThat(orderStatusOf(orderId)).isEqualTo(orderStatus);
        assertThat(reservationsOf(orderId)).containsExactly(reservation);
        assertThat(stockOf(airMax)).isEqualTo(stock);
        assertThat(PG.createCalls()).isEqualTo(1);
    }

    @DisplayName("S-6 고객 확인(lookup)이 먼저 반영하고 callback이 뒤늦게 2번 와도 최종 상태는 같고 반영은 한 번이다:")
    @ParameterizedTest(name = "PG {0} → 주문 {1}, 예약 {2} 1행, 재고 {3}")
    @CsvSource({
        "SUCCESS, CONFIRMED, CONSUMED, 3",
        "FAILED, PAYMENT_FAILED, RELEASED, 5",
    })
    void lookupFirst(String pgStatus, OrderStatus orderStatus, String reservation, int stock) throws Exception {
        // arrange
        Long orderId = confirmAndAwait(2, "attempt-1", PaymentStatus.PENDING);
        String transactionKey = paymentOf(orderId).getTransactionKey();
        PG.complete(transactionKey, pgStatus, null);

        // act
        reconcile(orderId)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.paymentStatus").value(pgStatus))
            .andExpect(jsonPath("$.data.orderStatus").value(orderStatus.name()));
        int late = PG.sendCallback(transactionKey);
        int duplicate = PG.sendCallback(transactionKey);

        // assert
        assertThat(late).isEqualTo(200);
        assertThat(duplicate).isEqualTo(200);
        assertThat(orderStatusOf(orderId)).isEqualTo(orderStatus);
        assertThat(reservationsOf(orderId)).containsExactly(reservation);
        assertThat(stockOf(airMax)).isEqualTo(stock);
        assertThat(PG.createCalls()).isEqualTo(1);
    }

    @DisplayName("S-6 FAILED callback 5번과 고객 확인 5번이 동시에 반영해도 모두 정상 응답이고, 주문 PAYMENT_FAILED·예약 RELEASED 1행·재고 5다 (7이면 중복 반환).")
    @Test
    void concurrentCallbacksAndLookups() throws Exception {
        // arrange
        Long orderId = confirmAndAwait(2, "attempt-1", PaymentStatus.PENDING);
        String transactionKey = paymentOf(orderId).getTransactionKey();
        PG.complete(transactionKey, "FAILED", "한도 초과");
        List<Integer> callbackStatuses = Collections.synchronizedList(new ArrayList<>());
        List<Callable<?>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tasks.add(() -> callbackStatuses.add(PG.sendCallback(transactionKey)));
            tasks.add(() -> paymentReconcileUseCase.reconcile(user.getId(), orderId));
        }

        // act
        List<Outcome> outcomes = ConcurrentRunner.run(tasks);

        // assert
        assertThat(ConcurrentRunner.count(outcomes, Kind.SUCCESS)).isEqualTo(10);
        assertThat(callbackStatuses).hasSize(5).containsOnly(200);
        assertThat(paymentOf(orderId).getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(orderStatusOf(orderId)).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(reservationsOf(orderId)).containsExactly("RELEASED");
        assertThat(stockOf(airMax)).isEqualTo(5);
        assertThat(PG.createCalls()).isEqualTo(1);
    }
}
