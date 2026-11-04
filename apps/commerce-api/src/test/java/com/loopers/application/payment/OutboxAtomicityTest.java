package com.loopers.application.payment;

import com.loopers.concurrency.ConcurrentRunner;
import com.loopers.concurrency.ConcurrentRunner.Outcome;
import com.loopers.concurrency.ConcurrentRunner.WorkerResult;
import com.loopers.domain.order.OrderConfirmedEvent;
import com.loopers.domain.order.OrderEventSerializer;
import com.loopers.domain.order.OrderOutboxModel;
import com.loopers.domain.order.OrderOutboxRepository;
import com.loopers.domain.order.OrderOutboxService;
import com.loopers.domain.order.OrderOutboxStatus;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.ReservationStatus;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.support.pg.TestPgStatus;
import com.loopers.support.pg.TestPgTransaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 주문 확정과 Outbox 기록이 한 로컬 트랜잭션으로 함께 commit·rollback되는지 relay 없이 실제 MySQL에서 확인한다 (W7 과제 2·6절, design.md 3절).
 *
 * <p>Outbox 저장 직전·직후와 직렬화에서 실패를 주입한다. 실패하면 확정 트랜잭션 직전의 미완료 주문·시도·예약이 그대로 남아야 하고(주문 행이 지워지는 것이 아니다),
 * PG에 남은 SUCCESS는 같은 거래를 다시 조회해 한 번만 확정해야 한다. 새 PG 결제는 만들지 않는다.
 */
class OutboxAtomicityTest extends PaymentScenarioTestBase {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @MockitoSpyBean
    private OrderOutboxRepository orderOutboxRepository;

    @MockitoSpyBean
    private OrderEventSerializer orderEventSerializer;

    @Autowired
    private OrderOutboxService orderOutboxService;

    /** 확정 트랜잭션이 건드리는 상태와 PG 호출 수를 한 번에 본다. rollback 전후를 같은 모양으로 비교한다. */
    private record Snapshot(
        OrderStatus order,
        PaymentAttemptStatus attempt,
        ReservationStatus reservation,
        int stock,
        long outboxRows,
        int pgCreates,
        int pgTransactions
    ) {
    }

    private Snapshot snapshot() {
        return new Snapshot(
            order().status(), attempt().getStatus(), reservation(), stock(), orderOutboxJpaRepository.count(), pg.createCount(), pg.transactionCount()
        );
    }

    /** 결제를 시작하고 PG에서 SUCCESS로 끝낸다. 아직 우리 쪽에는 결과를 적용하지 않은 상태다. */
    private Snapshot pgSucceededButNotApplied() throws Exception {
        confirm().andExpect(status().isAccepted());
        pgCompletes(TestPgStatus.SUCCESS);
        Snapshot before = snapshot();
        assertThat(before).isEqualTo(new Snapshot(
            OrderStatus.PAYMENT_PENDING, PaymentAttemptStatus.PENDING, ReservationStatus.RESERVED, INITIAL_STOCK - ORDER_QUANTITY, 0, 1, 1
        ));
        return before;
    }

    // 실패를 걷어 낸 뒤 같은 거래를 다시 조회하면 한 번만 확정되고, PG에는 새 결제가 생기지 않는다
    private void recoversOnceBySameTransactionLookup() throws Exception {
        reconcile().andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CONFIRMED"));

        assertThat(snapshot()).isEqualTo(new Snapshot(
            OrderStatus.CONFIRMED, PaymentAttemptStatus.SUCCESS, ReservationStatus.CONSUMED, INITIAL_STOCK - ORDER_QUANTITY, 1, 1, 1
        ));
        assertThat(outboxRows()).extracting(OrderOutboxModel::getEventId, OrderOutboxModel::getStatus)
            .containsExactly(tuple(OrderConfirmedEvent.eventIdOf(orderId), OrderOutboxStatus.PENDING));
    }

    @DisplayName("확정 트랜잭션 안에서 Outbox 기록이 실패하면, ")
    @Nested
    class WhenOutboxWriteFails {

        @DisplayName("insert 전에 실패해도 주문·시도·예약·재고가 트랜잭션 직전 그대로이고 Outbox·PG 호출이 늘지 않는다. 같은 거래 재조회로 한 번 확정한다.")
        @Test
        void rollsBackEverything_whenFailingBeforeInsert() throws Exception {
            // arrange
            Snapshot before = pgSucceededButNotApplied();
            doThrow(new IllegalStateException("Outbox 저장 실패(주입)")).when(orderOutboxRepository).save(any());

            // act
            assertThatThrownBy(() -> paymentFacade.reconcile(orderId, USER_ID)).isInstanceOf(IllegalStateException.class);

            // assert
            assertThat(snapshot()).as("확정·예약 소비·Outbox가 함께 rollback된다").isEqualTo(before);
            assertThat(orderJpaRepository.findById(orderId)).as("rollback은 기존 주문 행을 지우지 않는다").isPresent();
            assertThat(order().items()).hasSize(1);

            // act & assert: 장애가 풀린 뒤
            Mockito.reset(orderOutboxRepository);
            recoversOnceBySameTransactionLookup();
        }

        @DisplayName("insert가 실행된 뒤에 실패해도 그 행까지 함께 rollback된다. 같은 거래 재조회로 한 번 확정한다.")
        @Test
        void rollsBackInsertedRow_whenFailingAfterInsert() throws Exception {
            // arrange
            Snapshot before = pgSucceededButNotApplied();
            AtomicLong insertedId = new AtomicLong();
            doAnswer(invocation -> {
                OrderOutboxModel saved = (OrderOutboxModel) invocation.callRealMethod();
                insertedId.set(saved.getId());
                throw new IllegalStateException("Outbox insert 뒤 실패(주입)");
            }).when(orderOutboxRepository).save(any());

            // act
            assertThatThrownBy(() -> paymentFacade.reconcile(orderId, USER_ID)).isInstanceOf(IllegalStateException.class);

            // assert
            assertThat(insertedId.get()).as("실패 전에 insert가 실제로 실행됐다(IDENTITY 키를 받았다)").isPositive();
            assertThat(snapshot()).as("이미 insert한 행도 확정과 함께 rollback된다").isEqualTo(before);

            // act & assert: 장애가 풀린 뒤
            Mockito.reset(orderOutboxRepository);
            recoversOnceBySameTransactionLookup();
        }

        @DisplayName("payload 직렬화가 실패해도 같은 트랜잭션의 실패로 rollback된다. 같은 거래 재조회로 한 번 확정한다.")
        @Test
        void rollsBackEverything_whenSerializationFails() throws Exception {
            // arrange
            Snapshot before = pgSucceededButNotApplied();
            doThrow(new IllegalStateException("직렬화 실패(주입)")).when(orderEventSerializer).serialize(any());

            // act
            assertThatThrownBy(() -> paymentFacade.reconcile(orderId, USER_ID)).isInstanceOf(IllegalStateException.class);

            // assert
            assertThat(snapshot()).isEqualTo(before);

            // act & assert: 장애가 풀린 뒤
            Mockito.reset(orderEventSerializer);
            recoversOnceBySameTransactionLookup();
        }
    }

    @DisplayName("이번 적용이 처음 확정이 아니면, ")
    @Nested
    class WhenNotFirstConfirmation {

        @DisplayName("결과를 확인하지 못한(UNKNOWN) 시도는 Outbox를 남기지 않는다.")
        @Test
        void doesNotRecord_whenResultUnknown() throws Exception {
            // arrange
            pg.dropNextCreateResponse();

            // act
            confirm().andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status").value("PAYMENT_PENDING"));

            // assert
            assertThat(attempt().getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
            assertThat(outboxRows()).isEmpty();
        }

        @DisplayName("같은 거래의 callback·조회가 동시에 와도 확정·예약 소비·Outbox는 각각 한 번이다.")
        @Test
        void recordsOnce_whenSameResultArrivesConcurrently() throws Exception {
            // arrange
            confirm().andExpect(status().isAccepted());
            pgCompletes(TestPgStatus.SUCCESS);
            TestPgTransaction transaction = pgTransaction();
            List<Callable<PaymentOutcome>> tasks = List.of(
                () -> paymentFacade.handleCallback(transaction.transactionKey(), transaction.orderId()),
                () -> paymentFacade.handleCallback(transaction.transactionKey(), transaction.orderId()),
                () -> paymentFacade.handleCallback(transaction.transactionKey(), transaction.orderId()),
                () -> paymentFacade.reconcile(orderId, USER_ID),
                () -> paymentFacade.reconcile(orderId, USER_ID)
            );

            // act
            List<WorkerResult<PaymentOutcome>> results = ConcurrentRunner.runTogether(tasks, TIMEOUT);

            // assert
            assertThat(ConcurrentRunner.count(results, Outcome.SUCCESS)).as("모두 확정 결과로 답한다").isEqualTo(tasks.size());
            assertThat(snapshot()).isEqualTo(new Snapshot(
                OrderStatus.CONFIRMED, PaymentAttemptStatus.SUCCESS, ReservationStatus.CONSUMED, INITIAL_STOCK - ORDER_QUANTITY, 1, 1, 1
            ));
        }
    }

    @DisplayName("Outbox 기록은 확정 트랜잭션 밖에서 부르면 새 트랜잭션을 열지 않고 거부한다.")
    @Test
    void refusesToAppendOutsideConfirmationTransaction() {
        // arrange
        OrderConfirmedEvent event = new OrderConfirmedEvent(
            OrderConfirmedEvent.EVENT_TYPE,
            OrderConfirmedEvent.SCHEMA_VERSION,
            OrderConfirmedEvent.eventIdOf(orderId),
            orderId,
            "2026-09-07T01:00:00Z",
            List.of(new OrderConfirmedEvent.Item(product.getId(), ORDER_QUANTITY))
        );

        // act & assert
        assertThatThrownBy(() -> orderOutboxService.append(event, Instant.parse("2026-09-07T01:00:00Z")))
            .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(orderOutboxJpaRepository.count()).isZero();
    }
}
