package com.loopers.application.purchase;

import com.loopers.application.purchase.port.DuplicateConfirmationException;
import com.loopers.application.purchase.port.PurchaseActivityRepository;
import com.loopers.infrastructure.purchase.PurchaseActivityPersistenceAdapter;
import com.loopers.support.PurchaseActivityTables;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.loopers.application.purchase.PurchaseActivityHandlerTest.event;
import static com.loopers.application.purchase.PurchaseActivityHandlerTest.source;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 구매 활동 저장소를 실패·경쟁 주입용으로 바꿔 끼운 테스트만 모은 클래스. 실패는 저장소 대역에서만 일으키고 handler는 그대로 쓴다.
@SpringBootTest
@Import(PurchaseActivityHandlerTransactionTest.FailureInjectionConfig.class)
class PurchaseActivityHandlerTransactionTest {

    private static final int CONCURRENT_ROUNDS = 20;

    @Autowired
    private PurchaseActivityHandler purchaseActivityHandler;

    @Autowired
    private ControllableActivityRepository controllableActivityRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private PurchaseActivityTables tables;

    @BeforeEach
    void setUp() {
        tables = new PurchaseActivityTables(jdbcTemplate);
        controllableActivityRepository.reset();
    }

    @AfterEach
    void tearDown() {
        controllableActivityRepository.reset();
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("두 번째 품목 저장 직전에 실패하면 이미 DB로 나간 완료 기록과 첫 품목까지 모두 rollback되고 실패는 중복으로 바뀌지 않는다")
    @Test
    void failureBeforeSecondItemRollsBackEverything() {
        controllableActivityRepository.failOnActivitySave(2);

        assertThatThrownBy(() -> purchaseActivityHandler.consume(event("evt-42", 42, 7, 2, 8, 1), source(0)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage(ControllableActivityRepository.INJECTED_FAILURE);

        assertThat(controllableActivityRepository.stateBeforeFailure()).isEqualTo("confirmation=1, activity=1");
        assertThat(tables.confirmations()).isZero();
        assertThat(tables.activities()).isZero();
        assertThat(tables.anomalies()).isZero();
    }

    @DisplayName("조회 뒤 다른 소비가 먼저 같은 이벤트를 commit해 유일 제약에 걸리면 새 트랜잭션에서 같은 사실로 판단해 아무것도 하지 않는다")
    @Test
    void uniqueViolationOfSameFactIsNoOp() {
        purchaseActivityHandler.consume(event("evt-42", 42, 7, 2, 8, 1), source(0));
        controllableActivityRepository.hideNextLookups(2);

        ConsumeResult result = purchaseActivityHandler.consume(event("evt-42", 42, 7, 2, 8, 1), source(1));

        assertThat(result).isEqualTo(ConsumeResult.ALREADY_PROCESSED);
        assertThat(controllableActivityRepository.duplicates()).isEqualTo(1);
        assertThat(tables.confirmations()).isEqualTo(1);
        assertThat(tables.activities()).isEqualTo(2);
        assertThat(tables.anomalies()).isZero();
    }

    @DisplayName("조회 뒤 유일 제약에 걸린 이벤트가 다른 내용이면 새 트랜잭션에서 CONFLICT_SAME_EVENT_ID 이상을 남기고 구매 활동은 그대로다")
    @Test
    void uniqueViolationOfDifferentContentIsConflict() {
        purchaseActivityHandler.consume(event("evt-42", 42, 7, 2, 8, 1), source(0));
        controllableActivityRepository.hideNextLookups(2);

        ConsumeResult result = purchaseActivityHandler.consume(event("evt-42", 42, 7, 5), source(1));

        assertThat(result).isEqualTo(ConsumeResult.CONFLICT_SAME_EVENT_ID);
        assertThat(controllableActivityRepository.duplicates()).isEqualTo(1);
        assertThat(tables.activities()).isEqualTo(2);
        assertThat(tables.quantityOf(7)).isEqualTo(2);
        assertThat(tables.anomalies()).isEqualTo(1);
    }

    @DisplayName("같은 이벤트를 두 스레드가 동시에 소비해도 주문마다 완료 기록 1행·구매 활동 2행만 남고 이상 기록은 없다")
    @Test
    void concurrentConsumersOfSameEvent() throws Exception {
        Map<ConsumeResult, Integer> results = new EnumMap<ConsumeResult, Integer>(ConsumeResult.class);
        List<Throwable> technicalErrors = new ArrayList<Throwable>();
        int timeouts = 0;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < CONCURRENT_ROUNDS; round++) {
                OrderConfirmedEvent event = event("evt-" + (1000 + round), 1000 + round, 7, 2, 8, 1);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<ConsumeResult>> futures = new ArrayList<Future<ConsumeResult>>();
                for (int consumer = 0; consumer < 2; consumer++) {
                    long offset = round * 2L + consumer;
                    futures.add(executor.submit(() -> {
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new TimeoutException("시작 신호 대기 초과");
                        }
                        return purchaseActivityHandler.consume(event, source(offset));
                    }));
                }
                start.countDown();
                for (Future<ConsumeResult> future : futures) {
                    try {
                        results.merge(future.get(20, TimeUnit.SECONDS), 1, Integer::sum);
                    } catch (TimeoutException e) {
                        timeouts++;
                    } catch (Exception e) {
                        technicalErrors.add(e);
                    }
                }
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        System.out.println("[동시 소비] 결과=" + results + ", 기술 오류=" + technicalErrors.size() + ", timeout=" + timeouts
            + ", 유일 제약 경쟁=" + controllableActivityRepository.duplicates() + "/" + CONCURRENT_ROUNDS);
        assertThat(technicalErrors).isEmpty();
        assertThat(timeouts).isZero();
        assertThat(results).containsEntry(ConsumeResult.RECORDED, CONCURRENT_ROUNDS)
            .containsEntry(ConsumeResult.ALREADY_PROCESSED, CONCURRENT_ROUNDS)
            .hasSize(2);
        assertThat(tables.confirmations()).isEqualTo(CONCURRENT_ROUNDS);
        assertThat(tables.activities()).isEqualTo(CONCURRENT_ROUNDS * 2L);
        assertThat(tables.anomalies()).isZero();
    }

    @TestConfiguration
    static class FailureInjectionConfig {

        @Bean
        @Primary
        ControllableActivityRepository controllableActivityRepository(
            PurchaseActivityPersistenceAdapter purchaseActivityPersistenceAdapter,
            EntityManager entityManager,
            JdbcTemplate jdbcTemplate
        ) {
            return new ControllableActivityRepository(purchaseActivityPersistenceAdapter, entityManager, jdbcTemplate);
        }
    }

    // 실제 어댑터에 위임하면서 n번째 품목 저장 직전 실패, 다음 조회 숨김(다른 소비가 조회 뒤에 commit한 상황), 유일 제약 위반 횟수 관찰을 제공한다.
    static class ControllableActivityRepository implements PurchaseActivityRepository {

        static final String INJECTED_FAILURE = "주입된 구매 활동 저장 실패";

        private final PurchaseActivityRepository delegate;
        private final EntityManager entityManager;
        private final JdbcTemplate jdbcTemplate;
        private final AtomicInteger activitySaves = new AtomicInteger();
        private final AtomicInteger failingActivitySave = new AtomicInteger();
        private final AtomicInteger hiddenLookups = new AtomicInteger();
        private final AtomicInteger duplicates = new AtomicInteger();
        private final AtomicReference<String> stateBeforeFailure = new AtomicReference<String>();

        ControllableActivityRepository(PurchaseActivityRepository delegate, EntityManager entityManager, JdbcTemplate jdbcTemplate) {
            this.delegate = delegate;
            this.entityManager = entityManager;
            this.jdbcTemplate = jdbcTemplate;
        }

        void reset() {
            activitySaves.set(0);
            failingActivitySave.set(0);
            hiddenLookups.set(0);
            duplicates.set(0);
            stateBeforeFailure.set(null);
        }

        void failOnActivitySave(int nth) {
            activitySaves.set(0);
            failingActivitySave.set(nth);
        }

        void hideNextLookups(int count) {
            hiddenLookups.set(count);
        }

        int duplicates() {
            return duplicates.get();
        }

        String stateBeforeFailure() {
            return stateBeforeFailure.get();
        }

        @Override
        public Optional<ProcessedConfirmation> findConfirmationByEventId(String eventId) {
            if (hiddenLookups.getAndUpdate(count -> Math.max(0, count - 1)) > 0) {
                return Optional.empty();
            }
            return delegate.findConfirmationByEventId(eventId);
        }

        @Override
        public Optional<ProcessedConfirmation> findConfirmationByOrderId(long orderId) {
            if (hiddenLookups.getAndUpdate(count -> Math.max(0, count - 1)) > 0) {
                return Optional.empty();
            }
            return delegate.findConfirmationByOrderId(orderId);
        }

        @Override
        public void saveConfirmation(OrderConfirmedEvent event, Instant processedAt) {
            try {
                delegate.saveConfirmation(event, processedAt);
            } catch (DuplicateConfirmationException e) {
                duplicates.incrementAndGet();
                throw e;
            }
        }

        @Override
        public void saveActivity(long orderId, PurchasedItem item, Instant confirmedAt, Instant createdAt) {
            if (failingActivitySave.get() > 0 && activitySaves.incrementAndGet() == failingActivitySave.get()) {
                entityManager.flush();
                stateBeforeFailure.set("confirmation=" + count("processed_order_confirmation")
                    + ", activity=" + count("product_purchase_activity"));
                throw new IllegalStateException(INJECTED_FAILURE);
            }
            delegate.saveActivity(orderId, item, confirmedAt, createdAt);
        }

        private Long count(String table) {
            return jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
        }
    }
}
