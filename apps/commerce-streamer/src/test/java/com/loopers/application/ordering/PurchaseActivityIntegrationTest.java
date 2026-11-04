package com.loopers.application.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import com.loopers.application.ordering.facade.ConsumeOrderConfirmationFacade;
import com.loopers.application.ordering.service.RecordOrderEventFailureService;
import com.loopers.application.ordering.service.RecordPurchaseActivityService;
import com.loopers.application.ordering.service.VerifyProcessedOrderConfirmationService;
import com.loopers.application.ordering.usecase.ConsumeOrderConfirmationUseCase;
import com.loopers.application.ordering.usecase.ConsumeOrderConfirmationUseCase.Result;
import com.loopers.domain.ordering.model.OrderConfirmation;
import com.loopers.domain.ordering.model.OrderConfirmationConflictException;
import com.loopers.infrastructure.persistence.ordering.PurchaseActivityJpaRepository;
import com.loopers.infrastructure.persistence.ordering.OrderEventFailureJpaRepository;
import com.loopers.infrastructure.persistence.ordering.entity.OrderConfirmationInboxJpaEntity;
import com.loopers.infrastructure.messaging.ordering.OrderConfirmedKafkaConfiguration;
import com.loopers.interfaces.consumer.ordering.OrderConfirmedKafkaConsumer;
import com.loopers.testcontainers.KafkaTestFixture;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@Testcontainers
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = PurchaseActivityIntegrationTest.Config.class)
@ImportAutoConfiguration(KafkaAutoConfiguration.class)
@Sql(scripts = "/010-order-event-failure.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PurchaseActivityIntegrationTest {
    private static final Instant CONFIRMED_AT = Instant.parse("2026-11-04T01:00:00.123456Z");
    private static final String TOPIC = "order-events-test";
    private static final String GROUP = "purchase-activity-test";

    @Container
    private static final KafkaContainer KAFKA = KafkaTestFixture.create();

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("loopers")
        .withCommand("--log-bin-trust-function-creators=1")
        .withInitScript("009-purchase-activity.sql");

    @Autowired private ConsumeOrderConfirmationUseCase consumer;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private KafkaListenerEndpointRegistry listeners;
    @MockitoSpyBean private PurchaseActivityJpaRepository repository;

    @DynamicPropertySource
    static void dataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("order-events.topic", () -> TOPIC);
        registry.add("order-events.group", () -> GROUP);
    }

    @AfterEach
    void cleanUp() {
        sql("DROP TRIGGER IF EXISTS fail_second_purchase_item");
        sql("DROP TRIGGER IF EXISTS fail_order_event_archive");
        sql("DELETE FROM product_purchase_activities");
        sql("DELETE FROM order_confirmation_inbox");
        sql("DELETE FROM order_event_failures");
    }

    @Test
    @DisplayName("Kafka 재전달과 충돌·계약 오류를 처리하고 MySQL commit 뒤 offset을 진행한다")
    void consumesKafkaWithInboxAndDatabaseFailureArchive() throws Exception {
        var config = Map.<String, Object>of("bootstrap.servers", KAFKA.getBootstrapServers());
        byte[] payload;
        try (var fixture = getClass().getResourceAsStream("/order-confirmed-v1.json")) {
            payload = fixture.readAllBytes();
        }
        byte[] conflict = new String(payload, StandardCharsets.UTF_8)
            .replace("\"quantity\": 2", "\"quantity\": 3").getBytes(StandardCharsets.UTF_8);
        try (var producer = new KafkaProducer<>(config, new StringSerializer(), new ByteArraySerializer());
             var admin = Admin.create(config)) {
            for (byte[] value : List.of(payload, payload, conflict, new byte[]{42})) {
                producer.send(new ProducerRecord<>(TOPIC, 0, "42", value)).get(15, TimeUnit.SECONDS);
            }
            await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> {
                var offsets = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
                assertThat(offsets).containsKey(new TopicPartition(TOPIC, 0));
                assertThat(offsets.get(new TopicPartition(TOPIC, 0)).offset()).isEqualTo(4);
            });
            sql("""
                CREATE TRIGGER fail_order_event_archive BEFORE INSERT ON order_event_failures
                FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'test archive failure'
                """);
            producer.send(new ProducerRecord<>(TOPIC, 0, "42", null)).get(15, TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(15)).until(() ->
                !listeners.getListenerContainer(OrderConfirmedKafkaConsumer.LISTENER_ID).isRunning());
            var offsets = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
            assertThat(offsets.get(new TopicPartition(TOPIC, 0)).offset()).isEqualTo(4);
        }
        assertStored(event(UUID.fromString("e321d56a-59b3-4cf1-a7d0-6238451af280"), 42, CONFIRMED_AT, 2));
        assertThat(entityManager.createNativeQuery("""
            SELECT reason FROM order_event_failures ORDER BY source_offset
            """, String.class).getResultList()).containsExactly("FACT_CONFLICT", "INVALID_CONTRACT");
    }

    @Test
    @DisplayName("같은 이벤트를 다시 받아도 Inbox와 품목 수량은 한 번만 저장한다")
    void consumesTheSameEventOnce() {
        var event = event(UUID.randomUUID(), 42, CONFIRMED_AT, 2);

        assertThat(consumer.execute(event)).isEqualTo(Result.CREATED);
        assertThat(consumer.execute(event)).isEqualTo(Result.ALREADY_PROCESSED);

        assertStored(event);
    }

    @Test
    @DisplayName("UUID·주문·수량·시각 충돌은 기존 Inbox와 구매 활동을 보존한다")
    void rejectsConflictingFacts() {
        var original = event(UUID.randomUUID(), 42, CONFIRMED_AT, 2);
        consumer.execute(original);

        for (var conflicting : List.of(
            event(original.eventId(), 42, CONFIRMED_AT, 3),
            event(original.eventId(), 42, CONFIRMED_AT.plusSeconds(1), 2),
            event(original.eventId(), 43, CONFIRMED_AT, 2),
            event(UUID.randomUUID(), 42, CONFIRMED_AT, 2)
        )) {
            assertThatThrownBy(() -> consumer.execute(conflicting)).isInstanceOf(OrderConfirmationConflictException.class);
        }
        assertStored(original);
    }

    @Test
    @DisplayName("이벤트 ID와 주문 ID가 서로 다른 Inbox 행을 가리키면 충돌한다")
    void rejectsIdentityMatchedByDifferentRows() {
        var first = event(UUID.randomUUID(), 42, CONFIRMED_AT, 2);
        var second = event(UUID.randomUUID(), 43, CONFIRMED_AT, 3);
        consumer.execute(first);
        consumer.execute(second);

        assertThatThrownBy(() -> consumer.execute(event(first.eventId(), second.orderId(), CONFIRMED_AT, 3)))
            .isInstanceOf(OrderConfirmationConflictException.class);

        assertThat(count("order_confirmation_inbox")).isEqualTo(2);
        assertThat(count("product_purchase_activities")).isEqualTo(4);
        assertThat(repository.findCandidates(first.eventId(), first.orderId())).containsExactly(first);
        assertThat(repository.findCandidates(second.eventId(), second.orderId())).containsExactly(second);
    }

    @Test
    @DisplayName("동시 중복은 실제 유일 제약 경합 뒤 한 건만 커밋한다")
    void handlesConcurrentDuplicate() throws Exception {
        var original = event(UUID.randomUUID(), 42, CONFIRMED_AT, 2);
        synchronizeInitialReads();

        assertThat(race(() -> consumeOrReportConflict(original), () -> consumeOrReportConflict(original)))
            .containsExactlyInAnyOrder("CREATED", "ALREADY_PROCESSED");
        assertStored(original);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("동시 충돌은 이벤트 ID와 주문 ID 유일 제약 각각에서 확인한다")
    void handlesConcurrentConflict(boolean sameEventId) throws Exception {
        var first = event(UUID.randomUUID(), 42, CONFIRMED_AT, 2);
        var second = sameEventId ? event(first.eventId(), 43, CONFIRMED_AT, 2) : event(UUID.randomUUID(), 42, CONFIRMED_AT, 2);
        synchronizeInitialReads();

        var results = race(() -> consumeOrReportConflict(first), () -> consumeOrReportConflict(second));

        assertThat(results).containsExactlyInAnyOrder("CREATED", "CONFLICT");
        assertStored(results.getFirst().equals("CREATED") ? first : second);
    }

    @Test
    @DisplayName("첫 품목이 삽입된 뒤 두 번째 품목의 DB 오류가 발생하면 Inbox와 모든 품목이 롤백된다")
    void rollsBackInboxAndEarlierItem() {
        var event = event(UUID.randomUUID(), 42, CONFIRMED_AT, 2);
        sql("""
            CREATE TRIGGER fail_second_purchase_item BEFORE INSERT ON product_purchase_activities FOR EACH ROW
            BEGIN
                IF NEW.product_id = 8 AND EXISTS (
                    SELECT 1 FROM product_purchase_activities WHERE order_id = NEW.order_id AND product_id = 7
                ) THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'first item inserted before second item failed';
                END IF;
            END
            """);

        assertThatThrownBy(() -> consumer.execute(event))
            .hasStackTraceContaining("first item inserted before second item failed");

        assertThat(count("order_confirmation_inbox")).isZero();
        assertThat(count("product_purchase_activities")).isZero();
        sql("DROP TRIGGER fail_second_purchase_item");
        assertThat(consumer.execute(event)).isEqualTo(Result.CREATED);
        assertStored(event);
    }

    private void synchronizeInitialReads() {
        var reads = new AtomicInteger();
        var barrier = new CyclicBarrier(2);
        doAnswer(invocation -> {
            var candidates = invocation.callRealMethod();
            if (reads.getAndIncrement() < 2) {
                assertThat((List<?>) candidates).isEmpty();
                barrier.await(10, TimeUnit.SECONDS);
            }
            return candidates;
        }).when(repository).findCandidates(any(), anyLong());
    }

    private String consumeOrReportConflict(OrderConfirmation event) {
        try {
            return consumer.execute(event).name();
        } catch (OrderConfirmationConflictException exception) {
            return "CONFLICT";
        }
    }

    private List<String> race(Callable<String> first, Callable<String> second) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(first);
            var b = executor.submit(second);
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
    }

    private OrderConfirmation event(UUID eventId, long orderId, Instant confirmedAt, int quantity) {
        return new OrderConfirmation(eventId, orderId, confirmedAt,
            List.of(new OrderConfirmation.Item(7, quantity), new OrderConfirmation.Item(8, 1)));
    }

    private void assertStored(OrderConfirmation event) {
        assertThat(count("order_confirmation_inbox")).isEqualTo(1);
        assertThat(count("product_purchase_activities")).isEqualTo(2);
        assertThat(repository.findCandidates(event.eventId(), event.orderId())).containsExactly(event);
        List<?> facts = entityManager.createNativeQuery("""
            SELECT CONCAT(a.order_id, ':', a.product_id, ':', a.quantity)
            FROM product_purchase_activities a JOIN order_confirmation_inbox i ON a.inbox_id = i.id
            WHERE a.confirmed_at = i.confirmed_at
            ORDER BY a.product_id
            """, String.class).getResultList();
        assertThat(facts.stream().map(String.class::cast).toList())
            .containsExactly(event.orderId() + ":7:" + event.items().getFirst().quantity(), event.orderId() + ":8:1");
    }

    private long count(String table) {
        return ((Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM " + table).getSingleResult()).longValue();
    }

    private void sql(String statement) {
        new TransactionTemplate(transactions).executeWithoutResult(status ->
            entityManager.createNativeQuery(statement).executeUpdate());
    }

    @Configuration(proxyBeanMethods = false)
    @AutoConfigurationPackage(basePackageClasses = OrderConfirmationInboxJpaEntity.class)
    @EntityScan(basePackageClasses = OrderConfirmationInboxJpaEntity.class)
    @Import({ConsumeOrderConfirmationFacade.class, RecordPurchaseActivityService.class,
        VerifyProcessedOrderConfirmationService.class, PurchaseActivityJpaRepository.class,
        OrderConfirmedKafkaConfiguration.class, OrderConfirmedKafkaConsumer.class,
        RecordOrderEventFailureService.class, OrderEventFailureJpaRepository.class})
    static class Config {}
}
