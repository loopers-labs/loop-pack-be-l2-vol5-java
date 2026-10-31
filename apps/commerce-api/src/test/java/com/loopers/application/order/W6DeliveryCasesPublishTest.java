package com.loopers.application.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.user.UserRegistrationService;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderConfirmed;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.ReservationStatus;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.payment.PaymentAttemptJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.support.payment.FakePaymentServer;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * W6 발행 사례 재현 1단계 (W7 Outbox 비교용). 로컬 브로커 3대(docker/kafka-cluster-compose.yml)가 떠 있어야 한다.
 * <ul>
 *   <li>사례 A (미발행): 실제 PG 결과 적용으로 확정·commit한 뒤 send 직전 hook에서 멈춘다. 프로세스 강제 종료가 아니다.</li>
 *   <li>사례 B (정상): 같은 경로로 확정하고 실제 Kafka로 보낸다. 2단계에서 commerce-streamer가 소비한다.</li>
 * </ul>
 * 고정 조건: 확정 시각 {@link #NOW}, A = 양말 2·모자 1, B = 양말 1. topic {@link #TOPIC}(partition 3·복제 3·min ISR 2)을 매번 새로 만든다.
 * 실행: {@code ./gradlew :apps:commerce-api:experimentTest --tests '*W6DeliveryCasesPublishTest'}
 * 이어서: {@code ./gradlew :apps:commerce-streamer:experimentTest --tests '*W6DeliveryCasesConsumeTest'}
 */
@Tag("experiment")
@SpringBootTest(properties = {"commerce.order-events.enabled=true", "commerce.order-events.topic=" + W6DeliveryCasesPublishTest.TOPIC})
@Import(OrderConfirmedPublicationTest.StopBeforeSendConfig.class)
class W6DeliveryCasesPublishTest {
    static final String TOPIC = "order-events-w6-cases";
    static final String LOCAL_BROKERS = "localhost:19092,localhost:29092,localhost:39092";
    private static final Instant NOW = Instant.parse("2026-10-30T01:00:00.123456Z");
    private static final FakePaymentServer PG = startPg();

    @DynamicPropertySource
    static void endpoints(DynamicPropertyRegistry registry) {
        registry.add("commerce.payment.pg.base-url", PG::baseUrl);
        registry.add("spring.kafka.bootstrap-servers", () -> LOCAL_BROKERS);
    }

    @Autowired private UserRegistrationService registration;
    @Autowired private OrderFacade orders;
    @Autowired private BrandJpaRepository brands;
    @Autowired private ProductJpaRepository products;
    @Autowired private OrderJpaRepository orderRepository;
    @Autowired private PaymentAttemptJpaRepository attempts;
    @Autowired private OrderConfirmedPublicationTest.StopBeforeSend stopBeforeSend;
    @Autowired private KafkaTemplate<Object, Object> kafka;
    @Autowired private ObjectMapper mapper;
    @Autowired private DatabaseCleanUp cleanUp;
    @MockitoBean private Clock clock;

    @AfterEach
    void tearDown() {
        cleanUp.truncateAllTables();
    }

    @AfterAll
    static void stopPg() throws InterruptedException {
        PG.close();
    }

    @Test
    void confirmOneUnpublishedAndOnePublishedOrder() throws Exception {
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        recreateTopic();
        PG.reset();
        stopBeforeSend.reset();
        Long userId = registration.register().getId();
        Long brandId = brands.save(new BrandModel("W6 사례 브랜드", null)).getId();
        Long socks = products.save(new ProductModel(brandId, "양말", 1_000L, 10)).getId();
        Long cap = products.save(new ProductModel(brandId, "모자", 2_000L, 10)).getId();

        Long missed = confirm(userId, "w6-case-a", "TEST-TX-1",
            List.of(new OrderFacade.OrderLine(socks, 2), new OrderFacade.OrderLine(cap, 1)), true);
        Long published = confirm(userId, "w6-case-b", "TEST-TX-2",
            List.of(new OrderFacade.OrderLine(socks, 1)), false);
        kafka.flush();

        List<ConsumerRecord<String, String>> records = readAll();
        assertThat(records).extracting(ConsumerRecord::key).containsExactly(published.toString());
        OrderConfirmed missedEvent = stopBeforeSend.stopped().getFirst();
        assertThat(missedEvent.orderId()).isEqualTo(missed);

        report("A_UNPUBLISHED", missed, missedEvent.items(), null);
        ConsumerRecord<String, String> record = records.getFirst();
        report("B_PUBLISHED", published, mapper.readTree(record.value()).get("items"), record);
    }

    private Long confirm(Long userId, String attemptKey, String transactionKey,
                         List<OrderFacade.OrderLine> lines, boolean stopBeforeSending) {
        Long orderId = orders.create(userId, lines).id();
        orders.confirm(userId, orderId, new OrderFacade.PaymentCommand(attemptKey, "TEST", "0000-0000-0000-0000"));
        if (stopBeforeSending) {
            stopBeforeSend.stopFor(orderId);
        }
        PG.changeStatus(transactionKey, "SUCCESS");
        orders.reconcile(userId, orderId);
        var order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getReservationStatus()).isEqualTo(ReservationStatus.CONSUMED);
        return orderId;
    }

    private void report(String role, Long orderId, Object items, ConsumerRecord<String, String> record)
        throws Exception {
        var order = orderRepository.findById(orderId).orElseThrow();
        var attempt = attempts.findByOrderId(orderId).orElseThrow();
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("role", role);
        facts.put("orderId", orderId);
        facts.put("providerOrderId", attempt.getProviderOrderId());
        facts.put("transactionKey", attempt.getTransactionKey());
        facts.put("eventId", "order-confirmed:v1:" + orderId);
        facts.put("confirmedAt", order.getConfirmedAt().toString());
        facts.put("items", items);
        facts.put("orderStatus", order.getStatus());
        facts.put("reservationStatus", order.getReservationStatus());
        facts.put("broker", record == null ? "no record for this key"
            : Map.of("topic", record.topic(), "partition", record.partition(), "offset", record.offset()));
        System.out.println("W6_DELIVERY_CASE " + mapper.writeValueAsString(facts));
    }

    private void recreateTopic() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, LOCAL_BROKERS))) {
            if (admin.listTopics().names().get(10, TimeUnit.SECONDS).contains(TOPIC)) {
                admin.deleteTopics(List.of(TOPIC)).all().get(10, TimeUnit.SECONDS);
                long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                while (admin.listTopics().names().get(10, TimeUnit.SECONDS).contains(TOPIC)) {
                    assertThat(System.nanoTime()).isLessThan(deadline);
                    Thread.sleep(200);
                }
            }
            admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 3)
                .configs(Map.of("min.insync.replicas", "2")))).all().get(10, TimeUnit.SECONDS);
        }
        kafka.getProducerFactory().reset();
    }

    private List<ConsumerRecord<String, String>> readAll() {
        var partitions = List.of(new TopicPartition(TOPIC, 0), new TopicPartition(TOPIC, 1), new TopicPartition(TOPIC, 2));
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, LOCAL_BROKERS,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false))) {
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            long total = consumer.endOffsets(partitions).values().stream().mapToLong(Long::longValue).sum();
            List<ConsumerRecord<String, String>> records = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (records.size() < total && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            }
            return records;
        }
    }

    private static FakePaymentServer startPg() {
        try {
            return new FakePaymentServer();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
