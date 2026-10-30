package com.loopers.interfaces.consumer;

import com.loopers.application.purchase.OrderConfirmedEvent;
import com.loopers.application.purchase.PurchasedItem;
import com.loopers.application.purchase.port.PurchaseActivityRepository;
import com.loopers.infrastructure.purchase.PurchaseActivityPersistenceAdapter;
import com.loopers.support.PurchaseActivityTables;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.loopers.support.OrderConfirmedMessages.json;
import static org.assertj.core.api.Assertions.assertThat;

// 컨테이너를 멈췄다 다시 시작하는 장면이라 다른 listener 테스트와 topic·context를 나눈다.
@SpringBootTest
@Import(OrderEventsListenerTransientFailureTest.FailureInjectionConfig.class)
class OrderEventsListenerTransientFailureTest {

    private static final String TOPIC = "order-events-transient-" + UUID.randomUUID();

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Autowired
    private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @Autowired
    private FailingActivityRepository failingActivityRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private OrderEventsListenerFixture fixture;
    private PurchaseActivityTables tables;

    @DynamicPropertySource
    static void orderEventsTopic(DynamicPropertyRegistry registry) {
        registry.add("commerce.kafka.topics.order-events", () -> TOPIC);
    }

    @BeforeEach
    void setUp() {
        fixture = new OrderEventsListenerFixture(bootstrapServers, TOPIC, kafkaListenerEndpointRegistry);
        tables = new PurchaseActivityTables(jdbcTemplate);
        fixture.awaitAssigned();
    }

    @AfterEach
    void tearDown() {
        failingActivityRepository.disarm();
        fixture.close();
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("저장이 계속 실패하면 재시도 뒤 컨테이너가 멈추고 저장·offset이 그대로이며, 실패를 풀고 같은 group으로 재시작하면 같은 record가 저장되고 offset n+1이 된다")
    @Test
    void transientFailureStopsThenRestartRedelivers() throws Exception {
        long warmUp = fixture.send("41", json("evt-41", 41, 7, 1));
        fixture.awaitCommitted(warmUp + 1);

        failingActivityRepository.arm();
        long n = fixture.send("42", json("evt-42", 42, 7, 2, 8, 1));
        fixture.awaitStopped();

        Long committedAfterStop = fixture.committed();
        List<Instant> failures = failingActivityRepository.failures();
        assertThat(n).isEqualTo(warmUp + 1);
        assertThat(failures).hasSize(4);
        assertThat(committedAfterStop).isEqualTo(n);
        assertThat(tables.confirmations()).isEqualTo(1);
        assertThat(tables.activitiesOf(42)).isEmpty();

        failingActivityRepository.disarm();
        fixture.container().start();
        fixture.awaitCommitted(n + 1);

        System.out.println("[실험③ 일시적 실패] n=" + n + ", committed " + (warmUp + 1) + " → 정지 후 " + committedAfterStop
            + " → 재시작 후 " + fixture.committed() + ", 실패 시도 " + failures.size() + "회, 첫 실패~마지막 실패 "
            + Duration.between(failures.getFirst(), failures.getLast()).toMillis() + "ms");
        assertThat(tables.confirmations()).isEqualTo(2);
        assertThat(tables.activitiesOf(42)).hasSize(2);
        assertThat(tables.anomalies()).isZero();
    }

    @TestConfiguration
    static class FailureInjectionConfig {

        @Bean
        @Primary
        FailingActivityRepository failingActivityRepository(
            PurchaseActivityPersistenceAdapter purchaseActivityPersistenceAdapter,
            EntityManager entityManager
        ) {
            return new FailingActivityRepository(purchaseActivityPersistenceAdapter, entityManager);
        }
    }

    // 무장된 동안 품목 저장 직전에 앞선 완료 기록 INSERT를 DB로 보낸 뒤 실패해, rollback되는 일시적 DB 실패를 흉내 낸다.
    static class FailingActivityRepository implements PurchaseActivityRepository {

        private final PurchaseActivityRepository delegate;
        private final EntityManager entityManager;
        private final AtomicBoolean armed = new AtomicBoolean();
        private final List<Instant> failures = new ArrayList<Instant>();

        FailingActivityRepository(PurchaseActivityRepository delegate, EntityManager entityManager) {
            this.delegate = delegate;
            this.entityManager = entityManager;
        }

        void arm() {
            synchronized (failures) {
                failures.clear();
            }
            armed.set(true);
        }

        void disarm() {
            armed.set(false);
        }

        List<Instant> failures() {
            synchronized (failures) {
                return List.copyOf(failures);
            }
        }

        @Override
        public Optional<ProcessedConfirmation> findConfirmationByEventId(String eventId) {
            return delegate.findConfirmationByEventId(eventId);
        }

        @Override
        public Optional<ProcessedConfirmation> findConfirmationByOrderId(long orderId) {
            return delegate.findConfirmationByOrderId(orderId);
        }

        @Override
        public void saveConfirmation(OrderConfirmedEvent event, Instant processedAt) {
            delegate.saveConfirmation(event, processedAt);
        }

        @Override
        public void saveActivity(long orderId, PurchasedItem item, Instant confirmedAt, Instant createdAt) {
            if (armed.get()) {
                entityManager.flush();
                synchronized (failures) {
                    failures.add(Instant.now());
                }
                throw new IllegalStateException("주입된 일시적 저장 실패");
            }
            delegate.saveActivity(orderId, item, confirmedAt, createdAt);
        }
    }
}
