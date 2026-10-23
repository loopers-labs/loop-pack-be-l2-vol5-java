package com.loopers.application.ordering.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import com.loopers.application.ordering.command.PrepareOrderPaymentCommand;
import com.loopers.application.ordering.result.PrepareOrderPaymentResult;
import com.loopers.application.ordering.usecase.PrepareOrderPaymentUseCase;
import com.loopers.application.support.error.ApplicationErrorCode;
import com.loopers.application.support.error.ApplicationException;
import com.loopers.domain.mall.model.Brand;
import com.loopers.domain.mall.model.Product;
import com.loopers.domain.mall.model.StockAdjustment;
import com.loopers.domain.mall.repository.BrandRepository;
import com.loopers.domain.mall.repository.ProductRepository;
import com.loopers.domain.mall.repository.StockAdjustmentRepository;
import com.loopers.domain.ordering.model.Order;
import com.loopers.domain.ordering.model.OrderItem;
import com.loopers.domain.ordering.model.OrderStatus;
import com.loopers.domain.ordering.repository.OrderRepository;
import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentRecoveryTask;
import com.loopers.domain.pay.model.PaymentScenario;
import com.loopers.domain.pay.repository.PaymentAttemptRepository;
import com.loopers.domain.pay.repository.PaymentRecoveryTaskRepository;
import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import com.loopers.support.test.IntegrationTest;
import com.loopers.support.test.OrderConfirmProbe;
import com.loopers.utils.DatabaseCleanUp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class PrepareOrderPaymentIntegrationTest {
    @Autowired
    private PrepareOrderPaymentUseCase prepare;
    @Autowired
    private OrderRepository orders;
    @Autowired
    private ProductRepository products;
    @Autowired
    private BrandRepository brands;
    @Autowired
    private PaymentAttemptRepository attempts;
    @Autowired
    private StockAdjustmentRepository adjustments;
    @Autowired
    private PaymentRecoveryTaskRepository tasks;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private OrderConfirmProbe probe;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        reset(orders);
        databaseCleanUp.truncateAllTables();
    }

    @Test
    @DisplayName("동시 동일 요청은 재고와 준비 기록을 한 번 저장하고 최초 commit 실행에만 발송 권한을 준다")
    void grantsDispatchOnlyToCommittedWinner() throws Exception {
        long productId = product(5);
        long orderId = order(productId, 2);
        PrepareOrderPaymentCommand command = command(orderId, UUID.randomUUID().toString());

        List<PrepareOrderPaymentResult> results = race(() -> prepare.execute(command), () -> prepare.execute(command));

        assertThat(results).filteredOn(PrepareOrderPaymentResult::dispatchAllowed).hasSize(1);
        assertThat(results).allSatisfy(result -> {
            assertThat(result.attempt().getDispatchState()).isEqualTo(PaymentAttempt.DispatchState.STARTED);
            assertThat(result.attempt().getAttemptKey()).isEqualTo(command.attemptKey());
        });
        assertThat(prepare.execute(command).dispatchAllowed()).isFalse();
        assertThat(products.findById(productId).orElseThrow().getStock()).isEqualTo(3);
        assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(readAttempt(orderId).getAmount()).isEqualTo(2_000L);
        StockAdjustment adjustment = tx().execute(status -> adjustments.findByOrderId(orderId).orElseThrow());
        assertThat(adjustment.getItems()).containsExactly(new StockAdjustment.Item(productId, 2));
        assertThat(adjustment.getState()).isEqualTo(StockAdjustment.State.DEDUCTED);
        PaymentRecoveryTask task = tx().execute(status -> tasks.findByKey(command.attemptKey()).orElseThrow());
        assertThat(task.getState()).isEqualTo(PaymentRecoveryTask.State.READY);
        assertThat(task.getUsedRounds()).isZero();
        assertThat(probe.countPaidOrderRecords(orderId)).isZero();
        assertThat(probe.countUsePointBills(1L, orderId)).isZero();
    }

    @Test
    @DisplayName("재요청 조건·키 변경은 충돌이고 타인 주문은 키 검사보다 먼저 같은 404로 거절한다")
    void rejectsChangedRequestAndChecksOwnerFirst() {
        long productId = product(5);
        long orderId = order(productId, 2);
        String key = UUID.randomUUID().toString();
        prepare.execute(command(orderId, key));
        assertThat(prepare.execute(command(orderId, key.toUpperCase(java.util.Locale.ROOT))).dispatchAllowed()).isFalse();
        assertThatThrownBy(() -> prepare.execute(command(orderId, UUID.randomUUID().toString())))
            .isInstanceOf(ApplicationException.class).extracting("errorCode").isEqualTo(ApplicationErrorCode.PAYMENT_REQUEST_CONFLICT);
        assertThatThrownBy(() -> prepare.execute(new PrepareOrderPaymentCommand(1L, orderId, key,
            PaymentScenario.resolve(PaymentScenario.Preset.NORMAL_FAILURE, null))))
            .isInstanceOf(ApplicationException.class).extracting("errorCode").isEqualTo(ApplicationErrorCode.PAYMENT_REQUEST_CONFLICT);
        assertThatThrownBy(() -> prepare.execute(new PrepareOrderPaymentCommand(2L, orderId, key, scenario())))
            .isInstanceOf(ApplicationException.class).extracting("errorCode").isEqualTo(ApplicationErrorCode.ORDER_NOT_FOUND);
        assertThat(products.findById(productId).orElseThrow().getStock()).isEqualTo(3);
    }

    @Test
    @DisplayName("서로 다른 주문의 같은 키 경합은 한 준비만 남기고 패자는 rollback 후 새 TX에서 충돌로 판정한다")
    void rollsBackGlobalKeyRace() throws Exception {
        long productId = product(5);
        long first = order(productId, 1);
        long second = order(productId, 1);
        String key = UUID.randomUUID().toString();
        CountDownLatch claimed = new CountDownLatch(2);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            claimed.countDown();
            if (!claimed.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("준비 선점 대기 초과"); }
            return result;
        }).when(orders).prepareIfDraft(anyLong(), anyLong());

        List<Object> results = race(() -> outcome(command(first, key)), () -> outcome(command(second, key)));

        assertThat(results).filteredOn(PrepareOrderPaymentResult.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(value -> value == ApplicationErrorCode.PAYMENT_REQUEST_CONFLICT).hasSize(1);
        assertThat(products.findById(productId).orElseThrow().getStock()).isEqualTo(4);
        long winner = results.stream().filter(PrepareOrderPaymentResult.class::isInstance)
            .map(PrepareOrderPaymentResult.class::cast).findFirst().orElseThrow().attempt().getOrderId();
        long loser = winner == first ? second : first;
        assertThat(orders.findById(loser).orElseThrow().getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(tx().<Optional<PaymentAttempt>>execute(status -> attempts.findByOrderId(loser))).isEmpty();
        assertThat(tx().<Optional<StockAdjustment>>execute(status -> adjustments.findByOrderId(loser))).isEmpty();
    }

    @Test
    @DisplayName("재고 부족이면 주문 선점도 취소하고 준비 기록과 발송 권한을 만들지 않는다")
    void rollsBackInsufficientStock() {
        long productId = product(1);
        long orderId = order(productId, 2);
        PrepareOrderPaymentCommand command = command(orderId, UUID.randomUUID().toString());

        assertThatThrownBy(() -> prepare.execute(command)).isInstanceOf(DomainException.class)
            .extracting("errorCode").isEqualTo(DomainErrorCode.INSUFFICIENT_STOCK);

        assertUnprepared(orderId, productId, 1, command.attemptKey());
    }

    @Test
    @DisplayName("마지막 복구 작업 insert 실패는 차감·시도·차감 기록까지 전부 취소한다")
    void rollsBackAfterAllPreparationWrites() {
        long productId = product(5);
        long orderId = order(productId, 2);
        String key = UUID.randomUUID().toString();
        tx().executeWithoutResult(status -> tasks.insert(PaymentRecoveryTask.create(key)));

        assertThatThrownBy(() -> prepare.execute(command(orderId, key))).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(products.findById(productId).orElseThrow().getStock()).isEqualTo(5);
        assertThat(tx().<Optional<PaymentAttempt>>execute(status -> attempts.findByOrderId(orderId))).isEmpty();
        assertThat(tx().<Optional<StockAdjustment>>execute(status -> adjustments.findByOrderId(orderId))).isEmpty();
        assertThat(tx().execute(status -> tasks.findByKey(key).orElseThrow()).getState()).isEqualTo(PaymentRecoveryTask.State.READY);
    }

    private void assertUnprepared(long id, long productId, int stock, String key) {
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(products.findById(productId).orElseThrow().getStock()).isEqualTo(stock);
        assertThat(tx().<Optional<PaymentAttempt>>execute(status -> attempts.findByOrderId(id))).isEmpty();
        assertThat(tx().<Optional<StockAdjustment>>execute(status -> adjustments.findByOrderId(id))).isEmpty();
        assertThat(tx().<Optional<PaymentRecoveryTask>>execute(status -> tasks.findByKey(key))).isEmpty();
    }

    private Object outcome(PrepareOrderPaymentCommand command) {
        try { return prepare.execute(command); }
        catch (ApplicationException exception) { return exception.getErrorCode(); }
    }

    private PaymentAttempt readAttempt(long id) {
        return tx().execute(status -> attempts.findByOrderId(id).orElseThrow());
    }

    private long product(int stock) {
        Brand brand = brands.save(Brand.create("브랜드", null));
        return products.save(Product.create(brand.getId(), "현재 상품", null, 1_000L, stock)).getId();
    }

    private long order(long productId, int quantity) {
        return orders.insert(Order.create(1L, List.of(OrderItem.create(productId, "주문 상품", 1_000L, quantity)))).getId();
    }

    private PrepareOrderPaymentCommand command(long orderId, String key) {
        return new PrepareOrderPaymentCommand(1L, orderId, key, scenario());
    }

    private PaymentScenario scenario() { return PaymentScenario.resolve(null, null); }
    private TransactionTemplate tx() { return new TransactionTemplate(transactionManager); }

    private <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> { start.await(); return first.call(); });
            var b = executor.submit(() -> { start.await(); return second.call(); });
            start.countDown();
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
    }
}
