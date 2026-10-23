package com.loopers.infrastructure.persistence.ordering.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.domain.ordering.model.Order;
import com.loopers.domain.ordering.model.OrderItem;
import com.loopers.domain.ordering.model.OrderRecord;
import com.loopers.domain.ordering.model.OrderStatus;
import com.loopers.domain.ordering.model.PaymentMethod;
import com.loopers.domain.ordering.repository.OrderRepository;
import com.loopers.support.test.IntegrationTest;
import com.loopers.support.test.OrderConfirmProbe;
import com.loopers.utils.DatabaseCleanUp;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class OrderPaymentCasRepositoryTest {
    @Autowired
    private OrderRepository repository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private OrderConfirmProbe probe;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Test
    @DisplayName("동시 준비와 성공 반영은 각각 한 번만 선점하고 PG 주문 기록 하나를 보존한다")
    void claimsAndCompletesOnce() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Order order = tx.execute(status -> repository.insert(newOrder()));
        long id = order.getId();
        assertThatThrownBy(() -> tx.execute(status -> repository.insert(order))).isInstanceOf(InvalidDataAccessApiUsageException.class).hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThat(tx.<Boolean>execute(status -> repository.prepareIfDraft(id, 2L))).isFalse();

        assertThat(race(() -> tx.execute(status -> repository.prepareIfDraft(id, 1L))))
            .containsExactlyInAnyOrder(true, false);
        assertThat(tx.execute(status -> repository.findById(id).orElseThrow()).getRecord()).isEmpty();
        assertThat(race(() -> tx.execute(status -> repository.completePgIfPending(id, OrderStatus.CONFIRMED,
            OrderRecord.paidByPg(1L, 2_000L))))).containsExactlyInAnyOrder(true, false);

        Order completed = tx.execute(status -> repository.findById(id).orElseThrow());
        assertThat(completed.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(completed.getRecord().orElseThrow().getPaymentMethod()).isEqualTo(PaymentMethod.PG);
        assertThat(completed.getRecord().orElseThrow().getAmount()).isEqualTo(2_000L);
        assertThat(probe.countPaidOrderRecords(id)).isEqualTo(1L);
        assertThat(tx.<Boolean>execute(status -> repository.completePgIfPending(id, OrderStatus.PAYMENT_FAILED, null))).isFalse();
    }

    @Test
    @DisplayName("준비·성공 반영의 rollback은 상태와 기록을 함께 취소하고 실패 종결은 기록 없이 한 번 저장한다")
    void rollsBackStateAndRecordTogether() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        long id = tx.execute(status -> repository.insert(newOrder())).getId();
        tx.executeWithoutResult(status -> {
            assertThat(repository.prepareIfDraft(id, 1L)).isTrue();
            status.setRollbackOnly();
        });
        assertThat(tx.execute(status -> repository.findById(id).orElseThrow()).getStatus()).isEqualTo(OrderStatus.DRAFT);
        tx.executeWithoutResult(status -> assertThat(repository.prepareIfDraft(id, 1L)).isTrue());
        tx.executeWithoutResult(status -> {
            assertThat(repository.completePgIfPending(id, OrderStatus.CONFIRMED, OrderRecord.paidByPg(1L, 2_000L))).isTrue();
            status.setRollbackOnly();
        });
        Order pending = tx.execute(status -> repository.findById(id).orElseThrow());
        assertThat(pending.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(pending.getRecord()).isEmpty();
        assertThat(probe.countPaidOrderRecords(id)).isZero();

        assertThat(tx.<Boolean>execute(status -> repository.completePgIfPending(id, OrderStatus.PAYMENT_FAILED, null))).isTrue();
        assertThat(tx.<Boolean>execute(status -> repository.completePgIfPending(id, OrderStatus.PAYMENT_FAILED, null))).isFalse();
        Order failed = tx.execute(status -> repository.findById(id).orElseThrow());
        assertThat(failed.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(failed.getRecord()).isEmpty();
        assertThat(failed.getItems().getFirst().getProductName()).isEqualTo("주문 당시 상품");
        assertThat(failed.getTotalAmount()).isEqualTo(2_000L);
    }

    @Test
    @DisplayName("PG 완료 기록의 결제수단·사용자·금액 또는 종결 조합이 다르면 대기 상태를 보존한다")
    void rejectsMismatchedCompletion() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        long id = tx.execute(status -> repository.insert(newOrder())).getId();
        tx.executeWithoutResult(status -> repository.prepareIfDraft(id, 1L));
        for (OrderRecord record : List.of(OrderRecord.paid(1L, 2_000L), OrderRecord.paidByPg(2L, 2_000L),
            OrderRecord.paidByPg(1L, 1_999L))) {
            assertThatThrownBy(() -> tx.execute(status -> repository.completePgIfPending(id, OrderStatus.CONFIRMED, record)))
                .isInstanceOf(InvalidDataAccessApiUsageException.class).hasRootCauseInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> tx.execute(status -> repository.completePgIfPending(id, OrderStatus.CONFIRMED, null)))
            .isInstanceOf(InvalidDataAccessApiUsageException.class).hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tx.execute(status -> repository.completePgIfPending(id, OrderStatus.DRAFT, null)))
            .isInstanceOf(InvalidDataAccessApiUsageException.class).hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tx.execute(status -> repository.completePgIfPending(id, OrderStatus.PAYMENT_FAILED,
            OrderRecord.paidByPg(1L, 2_000L)))).isInstanceOf(InvalidDataAccessApiUsageException.class).hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThat(tx.execute(status -> repository.findById(id).orElseThrow()).getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(probe.countPaidOrderRecords(id)).isZero();
    }

    private Order newOrder() {
        return Order.create(1L, List.of(OrderItem.create(1L, "주문 당시 상품", 1_000L, 2)));
    }

    private List<Boolean> race(Callable<Boolean> action) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<Boolean> request = () -> { start.await(); return action.call(); };
            var first = executor.submit(request);
            var second = executor.submit(request);
            start.countDown();
            return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
    }
}
