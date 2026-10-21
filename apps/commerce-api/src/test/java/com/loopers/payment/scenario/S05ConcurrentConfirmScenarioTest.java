package com.loopers.payment.scenario;

import com.loopers.order.domain.OrderStatus;
import com.loopers.payment.application.port.in.PaymentInfo;
import com.loopers.payment.application.port.in.PaymentRequestCommand;
import com.loopers.payment.application.port.in.PaymentRequestUseCase;
import com.loopers.payment.domain.PaymentStatus;
import com.loopers.support.concurrency.ConcurrentRunner;
import com.loopers.support.concurrency.ConcurrentRunner.Kind;
import com.loopers.support.concurrency.ConcurrentRunner.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S-5 같은 주문·시도 동시 요청 (plan.md 10장, 2장 식별자, C-4): 시도·예약 각 1, create 1, 다른 키·불일치 거절.
 * 동시 요청은 실제 Spring bean(TX A)을 스레드마다 불러 독립된 트랜잭션으로 겹치게 한다.
 */
@DisplayName("S-5 같은 주문·시도 동시 요청")
class S05ConcurrentConfirmScenarioTest extends PaymentScenarioSupport {

    private static final int THREADS = 10;

    @Autowired
    private PaymentRequestUseCase paymentRequestUseCase;

    private PaymentRequestCommand command(Long orderId, String key) {
        return new PaymentRequestCommand(user.getId(), orderId, key, "SAMSUNG", CARD_NO);
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    @DisplayName("S-5 같은 Idempotency-Key로 10번 동시에 확정하면 모두 같은 시도를 돌려받고, 결제·발송 장부·예약은 각 1행, PG create는 1번, 재고는 5→3이다.")
    @Test
    void sameKeyConcurrentlyMakesOneAttempt() throws Exception {
        // arrange
        Long orderId = createOrder(2);
        List<Long> paymentIds = Collections.synchronizedList(new ArrayList<>());
        List<Callable<?>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(() -> {
                PaymentInfo info = paymentRequestUseCase.request(command(orderId, "attempt-1"));
                return paymentIds.add(info.paymentId());
            });
        }

        // act
        List<Outcome> outcomes = ConcurrentRunner.run(tasks);
        awaitPayment(orderId, PaymentStatus.PENDING);

        // assert
        assertThat(ConcurrentRunner.count(outcomes, Kind.SUCCESS)).isEqualTo(THREADS);
        assertThat(paymentIds).hasSize(THREADS).containsOnly(paymentOf(orderId).getId());
        assertThat(count("payments")).isEqualTo(1);
        assertThat(count("payment_outbox")).isEqualTo(1);
        assertThat(reservationsOf(orderId)).containsExactly("RESERVED");
        assertThat(stockOf(airMax)).isEqualTo(3);
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(PG.transactionsCreated()).isEqualTo(1);
    }

    @DisplayName("S-5 같은 주문을 서로 다른 키 10개로 동시에 확정하면 1건만 접수되고 9건은 409(CONFLICT)이며, 결제·예약 각 1행, create 1번, 재고 3이다.")
    @Test
    void differentKeysConcurrentlyAcceptOnlyOne() throws Exception {
        // arrange
        Long orderId = createOrder(2);
        List<Callable<?>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            String key = "attempt-" + i;
            tasks.add(() -> paymentRequestUseCase.request(command(orderId, key)));
        }

        // act
        List<Outcome> outcomes = ConcurrentRunner.run(tasks);
        awaitPayment(orderId, PaymentStatus.PENDING);

        // assert
        assertThat(ConcurrentRunner.count(outcomes, Kind.SUCCESS)).isEqualTo(1);
        assertThat(ConcurrentRunner.count(outcomes, Kind.BUSINESS_REJECTED)).isEqualTo(THREADS - 1);
        assertThat(count("payments")).isEqualTo(1);
        assertThat(reservationsOf(orderId)).containsExactly("RESERVED");
        assertThat(stockOf(airMax)).isEqualTo(3);
        assertThat(PG.createCalls()).isEqualTo(1);
    }

    @DisplayName("S-5 HTTP로 같은 키를 다시 보내면 같은 시도(202, 같은 거래 키)이고, 같은 주문에 다른 키는 409, 그 키를 다른 주문에 쓰면 409이며 그 주문은 DRAFT·재고 그대로다.")
    @Test
    void keyMismatchesAreRejected() throws Exception {
        // arrange
        Long orderId = confirmAndAwait(2, "attempt-1", PaymentStatus.PENDING);
        String transactionKey = paymentOf(orderId).getTransactionKey();
        Long anotherOrderId = createOrder(1);

        // act & assert
        confirm(orderId, user, "attempt-1")
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.paymentStatus").value("PENDING"))
            .andExpect(jsonPath("$.data.transactionKey").value(transactionKey));
        confirm(orderId, user, "attempt-2").andExpect(status().isConflict());
        confirm(anotherOrderId, user, "attempt-1").andExpect(status().isConflict());

        assertThat(orderStatusOf(anotherOrderId)).isEqualTo(OrderStatus.DRAFT);
        assertThat(reservationsOf(anotherOrderId)).isEmpty();
        assertThat(count("payments")).isEqualTo(1);
        assertThat(stockOf(airMax)).isEqualTo(3);
        assertThat(PG.createCalls()).isEqualTo(1);
    }
}
