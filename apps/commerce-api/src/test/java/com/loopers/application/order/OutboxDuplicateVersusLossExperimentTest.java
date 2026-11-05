package com.loopers.application.order;

import com.loopers.application.user.UserRegistrationService;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderConfirmationPublisher;
import com.loopers.domain.order.OrderOutbox;
import com.loopers.domain.order.OrderOutboxDelivery;
import com.loopers.domain.order.OrderOutboxStatus;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.KafkaOrderConfirmedPublisher;
import com.loopers.infrastructure.order.OrderOutboxJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.support.payment.FakePaymentServer;
import com.loopers.testcontainers.KafkaTestBroker;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 중복을 줄이려는 두 선택이 각각 무엇을 대가로 내는지 실제 MySQL·Kafka에서 관찰한다.
 * <ul>
 *   <li>순서: PUBLISHED를 send 전에 저장하고 그 사이에 멈추면?</li>
 *   <li>기다림: broker 응답을 짧게만 기다리고 다음 시도로 넘어가면?</li>
 * </ul>
 * 제품 relay는 바꾸지 않는다. 순서 실험은 relay가 쓰는 짧은 TX와 publisher를 테스트가 직접 다른 순서로 호출해 만든다.
 * 중단은 다음 단계를 호출하지 않는 방식이며 OS 프로세스 종료가 아니다. consumer는 띄우지 않는다.
 * 실행: {@code ./gradlew :apps:commerce-api:experimentTest --tests '*OutboxDuplicateVersusLossExperimentTest'}
 */
@Tag("experiment")
@SpringBootTest(properties = "commerce.order-events.enabled=true")
class OutboxDuplicateVersusLossExperimentTest {
    private static final KafkaTestBroker BROKER = new KafkaTestBroker();
    private static final FakePaymentServer PG = startPg();
    private static final String TOPIC = "order-outbox-experiment-" + UUID.randomUUID();
    private static final Duration FIRST_RETRY = Duration.ofSeconds(2);
    private static final Duration MAX_RETRY = Duration.ofSeconds(60);
    private static final Duration BROKER_PAUSE = Duration.ofSeconds(10);

    @DynamicPropertySource
    static void endpoints(DynamicPropertyRegistry registry) {
        registry.add("commerce.payment.pg.base-url", PG::baseUrl);
        registry.add("commerce.order-events.topic", () -> TOPIC);
        registry.add("spring.kafka.bootstrap-servers", BROKER::bootstrapServers);
    }

    @Autowired private UserRegistrationService registration;
    @Autowired private OrderFacade orders;
    @Autowired private BrandJpaRepository brands;
    @Autowired private ProductJpaRepository products;
    @Autowired private OrderOutboxJpaRepository outbox;
    @Autowired private OrderOutboxTransactions transactions;
    @Autowired private OrderConfirmationPublisher publisher;
    @Autowired private KafkaTemplate<Object, Object> kafka;
    @Autowired private DatabaseCleanUp cleanUp;

    private Long userId;
    private Long productId;
    private int nextTransaction = 1;
    /** 토픽은 테스트끼리 공유하고 DB 정리로 orderId가 다시 1부터 시작하므로, 이 테스트가 시작한 뒤의 record만 센다. */
    private long firstOffset;

    @BeforeEach
    void setUp() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
            BROKER.bootstrapServers()))) {
            if (!admin.listTopics().names().get(10, TimeUnit.SECONDS).contains(TOPIC)) {
                admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
            }
        }
        firstOffset = endOffset();
        PG.reset();
        nextTransaction = 1;
        userId = registration.register().getId();
        Long brandId = brands.save(new BrandModel("outbox 실험 브랜드", null)).getId();
        productId = products.save(new ProductModel(brandId, "양말", 1_000L, 100)).getId();
    }

    @AfterEach
    void tearDown() {
        cleanUp.truncateAllTables();
    }

    @AfterAll
    static void closeResources() throws InterruptedException {
        try {
            PG.close();
        } finally {
            BROKER.close();
        }
    }

    /** 같은 중단 지점(두 단계 사이)에서 순서만 바꿔 비교한다. 두 경우 모두 재시작한 relay를 한 번 돌린다. */
    @Test
    void savingPublishedBeforeSendHidesTheEventWhileSendFirstRepublishesIt() {
        // 지금 구현의 순서: send → (중단) → PUBLISHED 저장 안 함
        Long sendFirst = confirmedOrder("send-first");
        Long sendFirstId = outboxOf(sendFirst).getId();
        OrderOutboxDelivery delivery = transactions.startAttempt(sendFirstId);
        publisher.publish(delivery);
        Map<String, Object> sendFirstBefore = observe(sendFirst);
        int sendFirstRestart = newRelay(publisher).runOnce();
        Map<String, Object> sendFirstAfter = observe(sendFirst);

        // 제안 순서: PUBLISHED 저장 → (중단) → send 안 함
        Long publishedFirst = confirmedOrder("published-first");
        Long publishedFirstId = outboxOf(publishedFirst).getId();
        transactions.startAttempt(publishedFirstId);
        transactions.markPublished(publishedFirstId);
        Map<String, Object> publishedFirstBefore = observe(publishedFirst);
        int publishedFirstRestart = newRelay(publisher).runOnce();
        Map<String, Object> publishedFirstAfter = observe(publishedFirst);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("sendFirst", Map.of("atStop", sendFirstBefore, "restartedRelaySent", sendFirstRestart,
            "afterRestart", sendFirstAfter));
        report.put("publishedFirst", Map.of("atStop", publishedFirstBefore, "restartedRelaySent", publishedFirstRestart,
            "afterRestart", publishedFirstAfter));
        System.out.println("OUTBOX_ORDER_EXPERIMENT " + report);

        assertThat(sendFirstBefore).containsEntry("status", OrderOutboxStatus.PENDING).containsEntry("records", 1);
        assertThat(sendFirstRestart).isEqualTo(1);
        assertThat(sendFirstAfter).containsEntry("status", OrderOutboxStatus.PUBLISHED).containsEntry("records", 2);

        assertThat(publishedFirstBefore).containsEntry("status", OrderOutboxStatus.PUBLISHED).containsEntry("records", 0);
        assertThat(publishedFirstRestart).as("PUBLISHED 행은 조회 대상이 아니다").isZero();
        assertThat(publishedFirstAfter).containsEntry("status", OrderOutboxStatus.PUBLISHED).containsEntry("records", 0);
    }

    /**
     * broker를 10초 얼린 동안 relay를 계속 돌린다. 응답 대기만 3초와 130초로 다르게 한다.
     * 짧게 기다리면 relay는 빨리 다음 시도로 넘어가지만, producer는 앞선 전송을 버리지 않고 계속 재시도한다.
     */
    @Test
    void shortSendWaitTurnsOneSlowDeliveryIntoSeveralRecords() throws Exception {
        warmUpProducerMetadata();

        Map<String, Object> shortWait = runWhileBrokerPaused(confirmedOrder("wait-3s"), Duration.ofSeconds(3));
        Map<String, Object> longWait = runWhileBrokerPaused(confirmedOrder("wait-130s"), Duration.ofSeconds(130));

        System.out.println("OUTBOX_WAIT_EXPERIMENT " + Map.of("sendWait3s", shortWait, "sendWait130s", longWait));

        assertThat((int) shortWait.get("records")).isGreaterThan(1);
        assertThat(shortWait).containsEntry("status", OrderOutboxStatus.PUBLISHED);
        assertThat(longWait).containsEntry("records", 1).containsEntry("status", OrderOutboxStatus.PUBLISHED);
    }

    private Map<String, Object> runWhileBrokerPaused(Long orderId, Duration sendWait) throws Exception {
        var relay = newRelay(new KafkaOrderConfirmedPublisher(kafka, TOPIC, sendWait));
        BROKER.pause();
        long started = System.nanoTime();
        CompletableFuture<Void> resume = CompletableFuture.runAsync(() -> {
            sleep(BROKER_PAUSE);
            BROKER.resume();
        });
        List<Long> runMillis = new ArrayList<>();
        try {
            long deadline = started + Duration.ofSeconds(150).toNanos();
            while (outboxOf(orderId).getStatus() != OrderOutboxStatus.PUBLISHED) {
                assertThat(System.nanoTime()).as("PUBLISHED까지 기한").isLessThan(deadline);
                long runStarted = System.nanoTime();
                relay.runOnce();
                runMillis.add(Duration.ofNanos(System.nanoTime() - runStarted).toMillis());
                sleep(Duration.ofMillis(200));
            }
        } finally {
            resume.get(30, TimeUnit.SECONDS);
        }
        long toPublishedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        sleep(Duration.ofSeconds(3)); // 늦게 끝난 앞선 전송이 있다면 기록될 시간을 준다

        Map<String, Object> result = new LinkedHashMap<>(observe(orderId));
        result.put("sendWaitSeconds", sendWait.toSeconds());
        result.put("toPublishedMillis", toPublishedMillis);
        result.put("longestRunMillis", runMillis.stream().mapToLong(Long::longValue).max().orElse(0));
        result.put("runsWithSlowSend", runMillis.stream().filter(millis -> millis >= 1_000).count());
        return result;
    }

    /** relay가 처음 send할 때 metadata 대기(max.block.ms)가 섞이지 않게 broker가 살아 있을 때 한 건을 보낸다. */
    private void warmUpProducerMetadata() {
        Long warmUp = confirmedOrder("warm-up");
        assertThat(newRelay(publisher).runOnce()).isEqualTo(1);
        assertThat(outboxOf(warmUp).getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED);
    }

    private Map<String, Object> observe(Long orderId) {
        OrderOutbox row = outboxOf(orderId);
        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("status", row.getStatus());
        observed.put("attemptCount", row.getAttemptCount());
        observed.put("records", recordsOf(row.getEventId()).size());
        observed.put("offsets", recordsOf(row.getEventId()).stream().map(ConsumerRecord::offset).toList());
        return observed;
    }

    private OrderOutboxRelay newRelay(OrderConfirmationPublisher sender) {
        return new OrderOutboxRelay(sender, transactions, 20, FIRST_RETRY, MAX_RETRY);
    }

    private Long confirmedOrder(String attemptKey) {
        Long orderId = orders.create(userId, List.of(new OrderFacade.OrderLine(productId, 1))).id();
        orders.confirm(userId, orderId, new OrderFacade.PaymentCommand(attemptKey, "TEST", "0000-0000-0000-0000"));
        PG.changeStatus("TEST-TX-" + nextTransaction++, "SUCCESS");
        orders.reconcile(userId, orderId);
        assertThat(outboxOf(orderId).getStatus()).isEqualTo(OrderOutboxStatus.PENDING);
        return orderId;
    }

    private OrderOutbox outboxOf(Long orderId) {
        return outbox.findAll().stream().filter(row -> row.getOrderId().equals(orderId)).findFirst().orElseThrow();
    }

    private List<ConsumerRecord<String, String>> recordsOf(String eventId) {
        var partition = new TopicPartition(TOPIC, 0);
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BROKER.bootstrapServers(),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false))) {
            consumer.assign(List.of(partition));
            consumer.seek(partition, firstOffset);
            long end = consumer.endOffsets(List.of(partition), Duration.ofSeconds(10)).get(partition);
            List<ConsumerRecord<String, String>> found = new ArrayList<>();
            while (consumer.position(partition) < end) {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> {
                    if (record.value().contains("\"eventId\":\"" + eventId + "\"")) {
                        found.add(record);
                    }
                });
            }
            return found;
        }
    }

    private long endOffset() {
        var partition = new TopicPartition(TOPIC, 0);
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BROKER.bootstrapServers(),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            return consumer.endOffsets(List.of(partition), Duration.ofSeconds(10)).get(partition);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
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
