package com.loopers.application.purchase;

import com.loopers.domain.purchase.OrderConfirmation;
import com.loopers.domain.purchase.ProductPurchaseActivity;
import com.loopers.domain.purchase.PurchaseActivityRepository;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(properties = "order.confirmed-event.auto-startup=false")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PurchaseActivityIntegrationTest {
    private static final Instant CONFIRMED_AT = Instant.parse("2026-09-07T01:00:00.123456Z");

    @Autowired
    private PurchaseActivityHandler handler;
    @MockitoSpyBean
    private PurchaseActivityRepository repository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("주문 42의 두 품목은 두 행이고, 같은 사실 재수신은 그대로이며, 주문 43은 별도 구매로 더한다.")
    @Test
    void recordsPurchasesOncePerOrderAndProduct() {
        OrderConfirmation first = confirmation("event-42", 42L, 2);

        handler.consume(first);

        assertThat(activities(42L)).isEqualTo(first.activities());
        assertCounts(1, 2);

        handler.consume(first);
        assertThat(activities(42L)).isEqualTo(first.activities());
        assertCounts(1, 2);

        handler.consume(new OrderConfirmation("event-43", 43L, CONFIRMED_AT,
            List.of(new OrderConfirmation.Item(7L, 3))));
        assertCounts(2, 3);
        assertThat(jdbc.queryForObject("select sum(quantity) from product_purchase_activity where product_id = 7", Long.class))
            .isEqualTo(5L);
    }

    @DisplayName("상품 순서와 분할 수량이 달라도 정규화한 같은 사실은 no-op이다.")
    @Test
    void acceptsEquivalentNormalizedFacts() {
        handler.consume(confirmation("event-42", 42L, 2));

        handler.consume(new OrderConfirmation("event-42", 42L, CONFIRMED_AT, List.of(
            new OrderConfirmation.Item(8L, 1), new OrderConfirmation.Item(7L, 1),
            new OrderConfirmation.Item(7L, 1))));

        assertCounts(1, 2);
        assertThat(activities(42L)).isEqualTo(confirmation("event-42", 42L, 2).activities());
    }

    @DisplayName("같은 이벤트·주문의 식별자나 품목·확정 시각이 충돌하면 기존 사실을 유지하고 오류를 낸다.")
    @ParameterizedTest
    @MethodSource("conflictingFacts")
    void rejectsConflictingFacts(OrderConfirmation conflicting) {
        OrderConfirmation original = confirmation("event-42", 42L, 2);
        handler.consume(original);

        assertThatThrownBy(() -> handler.consume(conflicting))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("충돌");

        assertCounts(1, 2);
        assertThat(activities(42L)).isEqualTo(original.activities());
        assertThat(activities(43L)).isEmpty();
    }

    static Stream<OrderConfirmation> conflictingFacts() {
        return Stream.of(
            confirmation("event-42", 43L, 2),
            confirmation("other-event", 42L, 2),
            confirmation("event-42", 42L, 3),
            new OrderConfirmation("event-42", 42L, CONFIRMED_AT.plusSeconds(1),
                List.of(new OrderConfirmation.Item(7L, 2), new OrderConfirmation.Item(8L, 1))),
            new OrderConfirmation("event-42", 42L, CONFIRMED_AT, List.of(new OrderConfirmation.Item(7L, 2))),
            new OrderConfirmation("event-42", 42L, CONFIRMED_AT,
                List.of(new OrderConfirmation.Item(7L, 2), new OrderConfirmation.Item(9L, 1))));
    }

    @DisplayName("두 번째 품목을 실제 저장한 뒤 오류가 나면 완료 기록과 모든 품목이 rollback된다.")
    @Test
    void rollsBackCompletionAndEveryItem() {
        AtomicInteger written = new AtomicInteger();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            if (written.incrementAndGet() == 2) {
                throw new DataAccessResourceFailureException("두 번째 품목 저장 뒤 테스트 DB 오류");
            }
            return null;
        }).when(repository).saveActivity(any());

        assertThatThrownBy(() -> handler.consume(confirmation("event-42", 42L, 2)))
            .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(written).hasValue(2);
        assertCounts(0, 0);
    }

    @DisplayName("중복 키가 아닌 DB 오류는 중복으로 숨기지 않고 그대로 전달한다.")
    @Test
    void propagatesUnrelatedDatabaseFailure() {
        doThrow(new DataAccessResourceFailureException("DB 연결 실패"))
            .when(repository).saveCompletion(any());

        assertThatThrownBy(() -> handler.consume(confirmation("event-42", 42L, 2)))
            .isInstanceOf(DataAccessResourceFailureException.class)
            .hasMessage("DB 연결 실패");

        assertCounts(0, 0);
    }

    @DisplayName("동시 소비가 모두 완료 기록 없음으로 출발해도 DB 유일 제약 뒤 새 TX 판정으로 두 요청이 완료된다.")
    @Test
    void handlesConcurrentDuplicateAfterUniqueConstraintRace() throws Exception {
        AtomicInteger initialReads = synchronizeFirstReads();
        OrderConfirmation event = confirmation("event-42", 42L, 2);

        List<Throwable> results = consumeTogether(event, event);

        assertThat(results).containsOnlyNulls();
        assertThat(initialReads.get()).isGreaterThanOrEqualTo(3);
        assertCounts(1, 2);
        assertThat(activities(42L)).isEqualTo(event.activities());
    }

    @DisplayName("다른 수량의 동시 소비에서는 승자 한 사실만 남고 패자는 rollback 뒤 충돌로 실패한다.")
    @Test
    void detectsConflictingConcurrentDelivery() throws Exception {
        synchronizeFirstReads();

        List<Throwable> results = consumeTogether(
            confirmation("event-42", 42L, 2), confirmation("event-42", 42L, 3));

        assertThat(results.stream().filter(result -> result == null).count()).isEqualTo(1);
        assertThat(results.stream().filter(result -> result != null).toList()).singleElement()
            .satisfies(failure -> assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessageContaining("충돌"));
        assertCounts(1, 2);
        assertThat(activities(42L).getFirst().quantity()).isIn(2, 3);
    }

    @DisplayName("다른 eventId로 같은 주문을 동시에 소비해도 orderId 유일 제약이 추가 구매를 막는다.")
    @Test
    void protectsOrderIdAgainstConcurrentDifferentEventIds() throws Exception {
        AtomicInteger reads = synchronizeFirstReads();

        List<Throwable> results = consumeTogether(
            confirmation("first-event", 42L, 2), confirmation("second-event", 42L, 2));

        assertThat(results.stream().filter(result -> result == null).count()).isEqualTo(1);
        assertThat(results.stream().filter(result -> result != null).toList()).singleElement()
            .satisfies(failure -> assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessageContaining("충돌"));
        assertThat(reads.get()).isGreaterThanOrEqualTo(3);
        assertCounts(1, 2);
    }

    @DisplayName("주문이 달라도 같은 eventId를 동시에 쓰면 eventId 유일 제약으로 한 사실만 남는다.")
    @Test
    void protectsEventIdAgainstConcurrentDifferentOrders() throws Exception {
        AtomicInteger reads = synchronizeFirstReads();

        List<Throwable> results = consumeTogether(
            confirmation("same-event", 42L, 2), confirmation("same-event", 43L, 2));

        assertThat(results.stream().filter(result -> result == null).count()).isEqualTo(1);
        assertThat(results.stream().filter(result -> result != null).toList()).singleElement()
            .satisfies(failure -> assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessageContaining("충돌"));
        assertThat(reads.get()).isGreaterThanOrEqualTo(3);
        assertCounts(1, 2);
    }

    @DisplayName("동일 주문·상품의 추가 활동 행은 DB 유일 제약이 거절한다.")
    @Test
    void enforcesActivityNaturalKeyInDatabase() {
        handler.consume(confirmation("event-42", 42L, 2));

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            repository.saveActivity(ProductPurchaseActivity.create(42L, 7L, 9, CONFIRMED_AT))))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertCounts(1, 2);
        assertThat(activities(42L).getFirst().quantity()).isEqualTo(2);
    }

    private AtomicInteger synchronizeFirstReads() {
        AtomicInteger reads = new AtomicInteger();
        CyclicBarrier barrier = new CyclicBarrier(2);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (reads.incrementAndGet() <= 2) {
                assertThat((List<?>) result).isEmpty();
                barrier.await(10, TimeUnit.SECONDS);
            }
            return result;
        }).when(repository).findProcessed(anyString(), anyLong());
        return reads;
    }

    private List<Throwable> consumeTogether(OrderConfirmation first, OrderConfirmation second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Throwable> a = executor.submit(() -> consumeAfter(start, first));
            Future<Throwable> b = executor.submit(() -> consumeAfter(start, second));
            start.countDown();
            return java.util.Arrays.asList(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Throwable consumeAfter(CountDownLatch start, OrderConfirmation event) throws InterruptedException {
        start.await();
        try {
            handler.consume(event);
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    private List<ProductPurchaseActivity> activities(long orderId) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            entityManager.flush();
            entityManager.clear();
            return repository.findActivities(orderId);
        });
    }

    private void assertCounts(int completions, int activities) {
        assertThat(jdbc.queryForObject("select count(*) from processed_order_confirmation", Integer.class))
            .isEqualTo(completions);
        assertThat(jdbc.queryForObject("select count(*) from product_purchase_activity", Integer.class))
            .isEqualTo(activities);
    }

    private static OrderConfirmation confirmation(String eventId, long orderId, int quantity) {
        return new OrderConfirmation(eventId, orderId, CONFIRMED_AT,
            List.of(new OrderConfirmation.Item(7L, quantity), new OrderConfirmation.Item(8L, 1)));
    }
}
