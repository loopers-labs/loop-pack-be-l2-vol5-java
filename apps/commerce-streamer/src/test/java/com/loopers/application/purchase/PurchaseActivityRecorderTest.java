package com.loopers.application.purchase;

import com.loopers.config.jpa.JpaConfig;
import com.loopers.domain.purchase.DuplicateOrderConfirmationException;
import com.loopers.domain.purchase.OrderPurchase;
import com.loopers.domain.purchase.OrderConfirmationConflictException;
import com.loopers.domain.purchase.ProcessedOrderConfirmation;
import com.loopers.domain.purchase.ProductPurchaseActivity;
import com.loopers.infrastructure.purchase.ProductPurchaseActivityJpaRepository;
import com.loopers.infrastructure.purchase.ProcessedOrderConfirmationJpaRepository;
import com.loopers.infrastructure.purchase.ProcessedOrderConfirmationRepositoryImpl;
import com.loopers.infrastructure.purchase.PurchaseActivityRepositoryImpl;
import com.loopers.interfaces.consumer.OrderConfirmedMessageParser;
import com.loopers.interfaces.consumer.OrderConfirmedRecordAdapter;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManagerFactory;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 실제 MySQL에서 구매 활동 저장과 rollback을 검증한다. Kafka listener는 시작하지 않는다. */
@SpringBootTest(classes = PurchaseActivityRecorderTest.DatabaseConfig.class)
class PurchaseActivityRecorderTest {
    private static final Instant CONFIRMED_AT = Instant.parse("2026-10-28T01:00:00.123456Z");

    @MockitoSpyBean private PurchaseActivityRecorder recorder;
    @Autowired private OrderConfirmationHandler handler;
    @Autowired private OrderConfirmedRecordAdapter recordAdapter;
    @Autowired private ProductPurchaseActivityJpaRepository activities;
    @Autowired private ProcessedOrderConfirmationJpaRepository confirmations;
    @Autowired private DatabaseCleanUp cleanUp;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @MockitoSpyBean private PurchaseActivityRepositoryImpl repository;
    @MockitoSpyBean private ProcessedOrderConfirmationRepositoryImpl completedRepository;

    @AfterEach
    void cleanUp() {
        cleanUp.truncateAllTables();
    }

    @Test
    void storesOneRowPerProductWithOriginalQuantityAndConfirmationTime() {
        handler.handle(order(42L, new OrderPurchase.Item(7L, 5), new OrderPurchase.Item(8L, 3)));

        assertThat(activities.findAll()).extracting(ProductPurchaseActivity::getOrderId,
            ProductPurchaseActivity::getProductId, ProductPurchaseActivity::getQuantity,
            ProductPurchaseActivity::getConfirmedAt).containsExactlyInAnyOrder(
                tuple(42L, 7L, 5, CONFIRMED_AT), tuple(42L, 8L, 3, CONFIRMED_AT));
        assertThat(confirmations.count()).isEqualTo(1);
        confirmations.findByEventId("order-confirmed:v1:42").orElseThrow()
            .verifySameFact(order(42L, new OrderPurchase.Item(7L, 5), new OrderPurchase.Item(8L, 3)));
    }

    @Test
    void repeatedEventIsNoOpEvenWhenItemsAreReorderedAndCombined() {
        handler.handle(order(42L, new OrderPurchase.Item(7L, 5), new OrderPurchase.Item(8L, 3)));
        List<Long> originalIds = activities.findAll().stream().map(ProductPurchaseActivity::getId).toList();
        Long completedId = confirmations.findByEventId("order-confirmed:v1:42").orElseThrow().getId();

        handler.handle(order(42L, new OrderPurchase.Item(8L, 3),
            new OrderPurchase.Item(7L, 2), new OrderPurchase.Item(7L, 3)));

        assertThat(activities.findAll()).extracting(ProductPurchaseActivity::getId)
            .containsExactlyInAnyOrderElementsOf(originalIds);
        assertThat(activities.findAll()).extracting(ProductPurchaseActivity::getProductId,
            ProductPurchaseActivity::getQuantity).containsExactlyInAnyOrder(tuple(7L, 5), tuple(8L, 3));
        assertThat(confirmations.count()).isEqualTo(1);
        assertThat(confirmations.findByEventId("order-confirmed:v1:42").orElseThrow().getId()).isEqualTo(completedId);
    }

    @Test
    void rawRecordAdapterCommitsPurchasesAndNoopsOnTheSameRecord() {
        byte[] payload = """
            {"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"order-confirmed:v1:42","orderId":42,
             "confirmedAt":"%s","items":[{"productId":7,"quantity":5},{"productId":8,"quantity":3}]}
            """.formatted(CONFIRMED_AT).getBytes(StandardCharsets.UTF_8);
        var record = new ConsumerRecord<>("order-events-test", 0, 12L, "42", payload);

        recordAdapter.handle(record);
        recordAdapter.handle(record);

        assertThat(confirmations.count()).isEqualTo(1);
        assertThat(activities.findAll()).extracting(ProductPurchaseActivity::getOrderId,
            ProductPurchaseActivity::getProductId, ProductPurchaseActivity::getQuantity,
            ProductPurchaseActivity::getConfirmedAt).containsExactlyInAnyOrder(
                tuple(42L, 7L, 5, CONFIRMED_AT), tuple(42L, 8L, 3, CONFIRMED_AT));
    }

    @Test
    void permitsDifferentProductsInOneOrderAndSameProductInAnotherOrder() {
        recorder.record(order(42L, new OrderPurchase.Item(7L, 5), new OrderPurchase.Item(8L, 3)));
        recorder.record(order(43L, new OrderPurchase.Item(7L, 2)));

        assertThat(activities.count()).isEqualTo(3);
        assertThat(activities.findAll().stream().filter(activity -> activity.getProductId().equals(7L))
            .mapToInt(ProductPurchaseActivity::getQuantity).sum()).isEqualTo(7);
    }

    @Test
    void databaseRejectsDuplicateOrderProductPairWithoutChangingStoredFacts() {
        OrderPurchase purchase = order(42L, new OrderPurchase.Item(7L, 5), new OrderPurchase.Item(8L, 3));
        recorder.record(purchase);

        assertThatThrownBy(() -> repository.save(new ProductPurchaseActivity(42L, 7L, 5, CONFIRMED_AT)))
            .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(activities.findAll()).extracting(ProductPurchaseActivity::getProductId,
            ProductPurchaseActivity::getQuantity).containsExactlyInAnyOrder(tuple(7L, 5), tuple(8L, 3));
    }

    @Test
    void failureOnSecondProductRollsBackAlreadyInsertedFirstProduct() {
        AtomicBoolean failOnce = new AtomicBoolean(true);
        doAnswer(invocation -> {
            ProductPurchaseActivity activity = invocation.getArgument(0);
            if (activity.getProductId().equals(8L) && failOnce.getAndSet(false)) {
                assertThat(activities.count()).isEqualTo(1);
                assertThat(confirmations.count()).isEqualTo(1);
                throw new IllegalStateException("test: second product failed");
            }
            return invocation.callRealMethod();
        }).when(repository).save(any());

        assertThatThrownBy(() -> handler.handle(order(42L,
            new OrderPurchase.Item(7L, 5), new OrderPurchase.Item(8L, 3))))
            .isInstanceOf(IllegalStateException.class).hasMessage("test: second product failed");

        assertThat(activities.count()).isZero();
        assertThat(confirmations.count()).isZero();

        handler.handle(order(42L, new OrderPurchase.Item(7L, 5), new OrderPurchase.Item(8L, 3)));
        assertThat(activities.count()).isEqualTo(2);
        assertThat(confirmations.count()).isEqualTo(1);
    }

    @Test
    void databaseFailureOnSecondProductRollsBackFirstProductAndPreservesExistingRow() {
        activities.save(new ProductPurchaseActivity(42L, 8L, 3, CONFIRMED_AT));

        assertThatThrownBy(() -> handler.handle(order(42L,
            new OrderPurchase.Item(7L, 5), new OrderPurchase.Item(8L, 3))))
            .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(activities.findAll()).extracting(ProductPurchaseActivity::getProductId,
            ProductPurchaseActivity::getQuantity).containsExactly(tuple(8L, 3));
        assertThat(confirmations.count()).isZero();
    }

    @Test
    void concurrentDuplicateWritesBothCompleteWithoutExtraPurchaseActivities() throws Exception {
        OrderPurchase purchase = order(42L, new OrderPurchase.Item(7L, 5), new OrderPurchase.Item(8L, 3));
        List<Object> writeTransactions = synchronizeBeforeCompletionInsert();
        List<Object> verificationTransactions = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            Object resource = TransactionSynchronizationManager.getResource(entityManagerFactory);
            assertThat(resource).isNotNull();
            verificationTransactions.add(resource);
            return invocation.callRealMethod();
        }).when(recorder).verifyRecorded(any());

        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> results = IntStream.range(0, 2).mapToObj(index -> executor.submit(() -> {
                handler.handle(purchase);
                return true;
            })).toList();
            assertThat(List.of(results.get(0).get(10, TimeUnit.SECONDS), results.get(1).get(10, TimeUnit.SECONDS)))
                .containsExactly(true, true);
        }

        assertThat(activities.findAll()).extracting(ProductPurchaseActivity::getProductId,
            ProductPurchaseActivity::getQuantity).containsExactlyInAnyOrder(tuple(7L, 5), tuple(8L, 3));
        assertThat(confirmations.count()).isEqualTo(1);
        verify(recorder, times(1)).verifyRecorded(purchase);
        assertThat(verificationTransactions).hasSize(1);
        assertThat(writeTransactions).hasSize(2).doesNotContain(verificationTransactions.getFirst());
    }

    @ParameterizedTest
    @MethodSource("conflictingPurchases")
    void conflictingIdentityOrFactsFailWithoutAdditionalPurchases(OrderPurchase incoming) {
        OrderPurchase original = order(42L, new OrderPurchase.Item(7L, 5), new OrderPurchase.Item(8L, 3));
        handler.handle(original);

        assertThatThrownBy(() -> handler.handle(incoming)).isInstanceOf(OrderConfirmationConflictException.class);

        assertThat(confirmations.count()).isEqualTo(1);
        confirmations.findByEventId(original.eventId()).orElseThrow().verifySameFact(original);
        assertThat(activities.findAll()).extracting(ProductPurchaseActivity::getOrderId,
            ProductPurchaseActivity::getProductId, ProductPurchaseActivity::getQuantity,
            ProductPurchaseActivity::getConfirmedAt).containsExactlyInAnyOrder(
                tuple(42L, 7L, 5, CONFIRMED_AT), tuple(42L, 8L, 3, CONFIRMED_AT));
    }

    static Stream<OrderPurchase> conflictingPurchases() {
        List<OrderPurchase.Item> items = List.of(new OrderPurchase.Item(7L, 5), new OrderPurchase.Item(8L, 3));
        String eventId = "order-confirmed:v1:42";
        return Stream.of(
            new OrderPurchase(eventId, 43L, CONFIRMED_AT, items),
            new OrderPurchase("other-event:42", 42L, CONFIRMED_AT, items),
            new OrderPurchase(eventId, 42L, CONFIRMED_AT.plusSeconds(1), items),
            new OrderPurchase(eventId, 42L, CONFIRMED_AT, List.of(
                new OrderPurchase.Item(7L, 9), new OrderPurchase.Item(8L, 3))),
            new OrderPurchase(eventId, 42L, CONFIRMED_AT, List.of(
                new OrderPurchase.Item(7L, 5), new OrderPurchase.Item(9L, 3))),
            new OrderPurchase(eventId, 42L, CONFIRMED_AT, List.of(new OrderPurchase.Item(7L, 5)))
        );
    }

    @Test
    void concurrentDifferentEventIdsForOneOrderExposeConflict() throws Exception {
        OrderPurchase first = order(42L, new OrderPurchase.Item(7L, 5), new OrderPurchase.Item(8L, 3));
        OrderPurchase otherId = new OrderPurchase("other-event:42", 42L, CONFIRMED_AT, first.items());
        synchronizeBeforeCompletionInsert();

        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<String>> results = Stream.of(first, otherId).map(purchase -> executor.submit(() -> {
                try {
                    handler.handle(purchase);
                    return "recorded";
                } catch (OrderConfirmationConflictException conflict) {
                    return "conflict";
                }
            })).toList();
            assertThat(List.of(results.get(0).get(10, TimeUnit.SECONDS), results.get(1).get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder("recorded", "conflict");
        }

        assertThat(confirmations.count()).isEqualTo(1);
        assertThat(activities.count()).isEqualTo(2);
        verify(recorder, times(1)).verifyRecorded(any());
    }

    @Test
    void identityCollisionWithoutAStoredFactCannotBeTreatedAsSuccess() {
        var competition = new DuplicateOrderConfirmationException(new IllegalStateException("test: missing winner"));
        doThrow(competition).when(completedRepository).save(any());
        OrderPurchase purchase = order(42L, new OrderPurchase.Item(7L, 5), new OrderPurchase.Item(8L, 3));

        assertThatThrownBy(() -> handler.handle(purchase)).isSameAs(competition);

        assertThat(confirmations.count()).isZero();
        assertThat(activities.count()).isZero();
        verify(recorder).verifyRecorded(purchase);
    }

    private List<Object> synchronizeBeforeCompletionInsert() {
        CountDownLatch ready = new CountDownLatch(2);
        List<Object> transactions = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            Object resource = TransactionSynchronizationManager.getResource(entityManagerFactory);
            assertThat(resource).isNotNull();
            transactions.add(resource);
            ready.countDown();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(completedRepository).save(any(ProcessedOrderConfirmation.class));
        return transactions;
    }

    private OrderPurchase order(Long orderId, OrderPurchase.Item... items) {
        return new OrderPurchase("order-confirmed:v1:" + orderId, orderId, CONFIRMED_AT, List.of(items));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = KafkaAutoConfiguration.class)
    @ComponentScan(basePackageClasses = JpaConfig.class)
    @Import({MySqlTestContainersConfig.class, DatabaseCleanUp.class,
        PurchaseActivityRecorder.class, PurchaseActivityRepositoryImpl.class,
        OrderConfirmationHandler.class, ProcessedOrderConfirmationRepositoryImpl.class,
        OrderConfirmedMessageParser.class, OrderConfirmedRecordAdapter.class})
    static class DatabaseConfig {}
}
