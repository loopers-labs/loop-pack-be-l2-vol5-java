package com.loopers.application.order;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.loopers.application.payment.PaymentResultApplier;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.ApplyResult;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.order.KafkaOrderConfirmedPublisher;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.pg.TestPg;
import com.loopers.testcontainers.KafkaTestContainers;
import com.loopers.utils.DatabaseCleanUp;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

/**
 * PG SUCCESS로 처음 확정된 주문만 commit 뒤에 OrderConfirmed로 나간다(docs/week6/design.md 3번, INV-601~603).
 * 실제 Facade·MySQL·테스트 PG·Kafka broker를 쓰고, 발행 이음매(OrderConfirmedPublisher)에만 스위치가 달린 래퍼를 끼운다
 * (docs/week6/decisions.md D-16 — 운영 코드에 테스트 분기가 없다). 래퍼는 기본으로 진짜 Kafka 구현에 그대로 넘긴다.
 *
 * <p>입력이 겹치지 않게 한다(D-13): topic은 이 클래스만의 고유 suffix이고, 테스트 사이에 DB를 비우지 않아 주문·상품 ID가
 * 클래스 안에서 겹치지 않는다(비우면 id가 1부터 다시 쓰여 앞 테스트의 eventId와 같아진다). 검증은 자기 주문 ID로만 한다.
 * DB는 클래스가 끝날 때 비운다.
 *
 * <p>"발행되지 않았다"는 잠으로 기다리지 않는다. 발행 스레드는 1개이고 큐는 FIFO라, 확인할 일 뒤에 표지(sentinel)를 넣고
 * 표지가 처리될 때까지 기다리면 그 앞의 일은 모두 처리가 끝난 것이다({@link #drainPublication()}).
 *
 * <p>미발행은 Counter `order.confirmed.unpublished{reason}`로도 센다(D-26). 컨텍스트가 테스트 사이에 이어지므로 Counter는
 * 행동 전후의 차이로 본다.
 */
@SpringBootTest
@Import(OrderConfirmedPublicationTest.SwitchConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderConfirmedPublicationTest {

    private static final String TOPIC = "order-events-it-" + UUID.randomUUID();
    private static final DateTimeFormatter CONTRACT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'");
    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final long PRICE = 1_000L;
    private static final int INITIAL_STOCK = 10;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @DynamicPropertySource
    static void topic(DynamicPropertyRegistry registry) {
        KafkaTestContainers.createTopic(TOPIC);
        registry.add("events.order-confirmed.topic", () -> TOPIC);
    }

    @Autowired
    private OrderFacade orderFacade;

    @Autowired
    private PaymentResultApplier paymentResultApplier;

    /**
     * 등록 실패(D-26)를 만드는 이음매. applier가 실제로 부르는 register 한 곳만 특정 주문에서 실패하게 하고, 나머지는 진짜
     * 구현 그대로 돈다. payload 재료는 Order 불변식이 보장하고 apply는 늘 트랜잭션이라, 운영 경로로는 이 실패를 만들 수 없다.
     */
    @MockitoSpyBean
    private OrderConfirmedPublication orderConfirmedPublication;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private SwitchablePublisher publisher;

    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private OrderJpaRepository orderJpaRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private final WireMockServer pg = TestPg.server();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger publicationLogger = (Logger) LoggerFactory.getLogger(OrderConfirmedUnpublished.class);
    private final AtomicLong sentinelIds = new AtomicLong();

    @BeforeEach
    void setUp() {
        publisher.reset();
        logs.list.clear();
        logs.start();
        publicationLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        publicationLogger.detachAppender(logs);
    }

    @AfterAll
    void cleanUp() {
        databaseCleanUp.truncateAllTables();
    }

    // ---------------------------------------------------------------- fixtures

    private record Fixture(Long userId, Long orderId, Map<Long, Integer> quantityByProductId) {
        String providerOrderId() {
            return String.format("LP-ORD-%010d", orderId);
        }

        String transactionKey() {
            return TestPg.transactionKeyOf(providerOrderId());
        }

        long amount() {
            return quantityByProductId.values().stream().mapToLong(quantity -> quantity * PRICE).sum();
        }

        String eventId() {
            return OrderConfirmedPayload.eventIdOf(orderId);
        }
    }

    /** 상품 2개(수량 2, 1)를 담은 주문을 PG 기본 응답(202 PENDING)으로 확정해 처리중으로 만든다. */
    private Fixture pending() {
        Fixture fixture = draft();
        orderFacade.confirmOrder(fixture.orderId(), fixture.userId(), command(fixture));
        assertThat(attemptStatus(fixture)).isEqualTo(PaymentAttemptStatus.PENDING);
        return fixture;
    }

    private Fixture draft() {
        Long userId = userJpaRepository.save(new UserModel()).getId();
        Map<Long, Integer> quantities = new HashMap<>();
        List<OrderItemRequest> items = new ArrayList<>();
        for (int quantity : new int[] {2, 1}) {
            Long productId = productJpaRepository.save(new ProductModel("상품", PRICE, 1L, INITIAL_STOCK)).getId();
            quantities.put(productId, quantity);
            items.add(new OrderItemRequest(productId, quantity));
        }
        Long orderId = orderFacade.createOrder(userId, items).id();
        return new Fixture(userId, orderId, quantities);
    }

    /** 처리중 결제를 고객 결제 확인으로 SUCCESS 반영한다 — 고객이 받는 응답을 돌려준다. */
    private OrderInfo confirmBySuccess(Fixture fixture) {
        stubSingle(fixture, "SUCCESS");
        return orderFacade.reconcilePayment(fixture.orderId(), fixture.userId());
    }

    /** 결제 시도 키는 주문마다 다르게 쓴다 — 테스트 사이에 DB를 비우지 않으므로 같은 키는 "다른 주문에 쓴 키"가 된다. */
    private static ConfirmPaymentCommand command(Fixture fixture) {
        return new ConfirmPaymentCommand("key-" + fixture.orderId(), "TEST", "0000-0000-0000-0000");
    }

    private void stubSingle(Fixture fixture, String status) {
        pg.stubFor(get(urlEqualTo(TestPg.PAYMENTS_PATH + "/" + fixture.transactionKey()))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody(transactionJson(fixture, status))));
    }

    private static String transactionJson(Fixture fixture, String status) {
        return "{\"transactionKey\":\"" + fixture.transactionKey() + "\",\"orderId\":\"" + fixture.providerOrderId()
            + "\",\"amount\":" + fixture.amount() + ",\"status\":\"" + status + "\"}";
    }

    private PaymentObservation success(Fixture fixture) {
        return new PaymentObservation(fixture.providerOrderId(), fixture.transactionKey(),
            PaymentObservation.Status.SUCCESS, fixture.amount());
    }

    private Order order(Fixture fixture) {
        return orderJpaRepository.findById(fixture.orderId()).orElseThrow();
    }

    private PaymentAttemptStatus attemptStatus(Fixture fixture) {
        return paymentAttemptRepository.findByOrderId(fixture.orderId()).orElseThrow().getStatus();
    }

    private int stockOf(Long productId) {
        return productJpaRepository.findById(productId).orElseThrow().getRemainingStock();
    }

    private void assertReservationConsumed(Fixture fixture) {
        fixture.quantityByProductId().forEach((productId, quantity) ->
            assertThat(stockOf(productId)).isEqualTo(INITIAL_STOCK - quantity));
    }

    private double unpublished(OrderConfirmedUnpublished.Reason reason) {
        return meterRegistry.get(OrderConfirmedUnpublished.METRIC)
            .tag(OrderConfirmedUnpublished.REASON_TAG, reason.tagValue())
            .counter().count();
    }

    // ---------------------------------------------------------------- 발행 스레드·topic 관찰

    /**
     * 발행 큐 뒤에 표지를 넣고 처리될 때까지 기다린다. 표지는 commit된 트랜잭션으로 등록해 실제 경로(afterCommit → 큐)를 지난다.
     * 표지가 처리됐다면 그 앞에 넣은 일은 모두 publisher까지 갔거나 갈 일이 없었던 것이다.
     */
    private void drainPublication() throws InterruptedException {
        long sentinelId = -sentinelIds.incrementAndGet();
        CountDownLatch latch = publisher.expectSentinel(sentinelId);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> orderConfirmedPublication.register(
            new OrderConfirmedPayload(OrderConfirmedPayload.EVENT_TYPE, OrderConfirmedPayload.SCHEMA_VERSION,
                OrderConfirmedPayload.eventIdOf(sentinelId), sentinelId, ZonedDateTime.now(ZoneOffset.UTC), List.of())));
        assertThat(latch.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)).as("발행 큐 비우기").isTrue();
    }

    private static KafkaConsumer<String, String> consumer() {
        return new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainers.bootstrapServers(),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false));
    }

    /** topic의 처음부터 "지금의 끝"까지 읽는다. group 없이 직접 배정하므로 offset을 commit하지 않는다. */
    private static List<ConsumerRecord<String, String>> readAll() {
        try (KafkaConsumer<String, String> consumer = consumer()) {
            List<TopicPartition> partitions = consumer.partitionsFor(TOPIC, WAIT).stream()
                .map(info -> new TopicPartition(TOPIC, info.partition()))
                .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions, WAIT);
            List<ConsumerRecord<String, String>> records = new ArrayList<>();
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (partitions.stream().anyMatch(tp -> consumer.position(tp, WAIT) < end.get(tp))) {
                assertThat(System.nanoTime()).as("topic 끝까지 읽기").isLessThan(deadline);
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            }
            return records;
        }
    }

    private static List<ConsumerRecord<String, String>> recordsOf(List<ConsumerRecord<String, String>> records, Fixture fixture) {
        return records.stream()
            .filter(record -> fixture.eventId().equals(eventIdOf(record)))
            .toList();
    }

    /** 자기 주문의 기록이 topic에 보일 때까지 유한하게 기다린다. */
    private static ConsumerRecord<String, String> awaitRecord(Fixture fixture) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            List<ConsumerRecord<String, String>> found = recordsOf(readAll(), fixture);
            if (!found.isEmpty()) {
                return found.get(0);
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        throw new AssertionError("topic에 기록이 나타나지 않았습니다: " + fixture.eventId());
    }

    private static String eventIdOf(ConsumerRecord<String, String> record) {
        try {
            return MAPPER.readTree(record.value()).path("eventId").asText();
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonNode json(ConsumerRecord<String, String> record) throws Exception {
        return MAPPER.readTree(record.value());
    }

    // ---------------------------------------------------------------- scenes

    @DisplayName("정상 1건: PG SUCCESS로 확정되면 key=orderId인 기록이 topic에 하나 남고, 내용은 DB에서 다시 읽은 사실과 글자 그대로 같다.")
    @Test
    void publishesOnce_withFactsEqualToDatabase() throws Exception {
        // arrange
        Fixture fixture = pending();

        // act
        OrderInfo response = confirmBySuccess(fixture);

        // assert
        ConsumerRecord<String, String> record = awaitRecord(fixture);
        System.out.printf("[OrderConfirmed 정상 1건] topic=%s, partition=%d, offset=%d, key=%s, value=%s%n",
            record.topic(), record.partition(), record.offset(), record.key(), record.value());

        Order stored = order(fixture);
        JsonNode body = json(record);
        List<Map<String, Long>> storedItems = stored.getItems().stream()
            .sorted(Comparator.comparing(OrderItem::getProductId))
            .map(item -> Map.of("productId", item.getProductId(), "quantity", (long) item.getQuantity()))
            .toList();
        List<Map<String, Long>> publishedItems = new ArrayList<>();
        body.get("items").forEach(item -> publishedItems.add(
            Map.of("productId", item.get("productId").asLong(), "quantity", item.get("quantity").asLong())));
        String storedConfirmedAt = stored.getConfirmedAt().withZoneSameInstant(ZoneOffset.UTC).format(CONTRACT_TIME);

        assertAll(
            () -> assertThat(response.status()).isEqualTo(OrderStatus.CONFIRMED),
            () -> assertThat(record.key()).isEqualTo(String.valueOf(fixture.orderId())),
            () -> assertThat(body.get("eventType").asText()).isEqualTo("OrderConfirmed"),
            () -> assertThat(body.get("schemaVersion").asInt()).isEqualTo(1),
            () -> assertThat(body.get("eventId").asText()).isEqualTo("order-confirmed:v1:" + fixture.orderId()),
            () -> assertThat(body.get("orderId").asLong()).isEqualTo(fixture.orderId()),
            () -> assertThat(body.get("confirmedAt").asText()).isEqualTo(storedConfirmedAt),
            () -> assertThat(publishedItems).isEqualTo(storedItems),
            () -> assertThat(recordsOf(readAll(), fixture)).hasSize(1),
            () -> assertThat(publisher.callsFor(fixture.orderId())).isEqualTo(1)
        );
    }

    @DisplayName("확정 순간의 메모리 시각(이벤트에 실린 값)과 고객 응답, DB에서 다시 읽은 시각이 같은 순간이고 글자도 같다. (D-6)")
    @Test
    void confirmedAtInMemoryEqualsDatabase() throws Exception {
        // arrange
        Fixture fixture = pending();

        // act
        OrderInfo response = confirmBySuccess(fixture);
        drainPublication();

        // assert
        ZonedDateTime inMemory = publisher.payloadOf(fixture.orderId()).confirmedAt();
        ZonedDateTime stored = order(fixture).getConfirmedAt();
        assertAll(
            () -> assertThat(inMemory.getZone()).isEqualTo(ZoneOffset.UTC),
            () -> assertThat(inMemory.toInstant()).isEqualTo(stored.toInstant()),
            () -> assertThat(response.confirmedAt().toInstant()).isEqualTo(stored.toInstant()),
            () -> assertThat(inMemory.format(CONTRACT_TIME))
                .isEqualTo(stored.withZoneSameInstant(ZoneOffset.UTC).format(CONTRACT_TIME))
        );
    }

    @DisplayName("미발행 1건 + 정상 1건: commit 뒤 send 직전에 멈추면 주문은 CONFIRMED·예약 소비로 남고 고객은 성공을 받지만, "
        + "topic에는 그 기록이 없다. 같은 조건의 다음 주문은 정상으로 나간다. (누락 구간, D-4·D-16)")
    @Test
    void haltBeforeSend_leavesConfirmedOrderWithoutRecord() throws Exception {
        // arrange
        Fixture halted = pending();
        Fixture normal = pending();

        // act
        publisher.mode(Mode.HALT_BEFORE_SEND);
        OrderInfo haltedResponse = confirmBySuccess(halted);
        drainPublication();
        publisher.mode(Mode.PASS_THROUGH);
        OrderInfo normalResponse = confirmBySuccess(normal);

        // assert
        awaitRecord(normal);
        List<ConsumerRecord<String, String>> records = readAll();
        assertAll(
            () -> assertThat(haltedResponse.status()).isEqualTo(OrderStatus.CONFIRMED),
            () -> assertThat(order(halted).getStatus()).isEqualTo(OrderStatus.CONFIRMED),
            () -> assertThat(attemptStatus(halted)).isEqualTo(PaymentAttemptStatus.SUCCESS),
            () -> assertReservationConsumed(halted),
            () -> assertThat(publisher.haltedOrderIds()).containsExactly(halted.orderId()),
            () -> assertThat(recordsOf(records, halted)).isEmpty(),
            () -> assertThat(normalResponse.status()).isEqualTo(OrderStatus.CONFIRMED),
            () -> assertThat(recordsOf(records, normal)).hasSize(1)
        );
    }

    @DisplayName("전송 실패 주입: 발행이 실패해도 주문은 CONFIRMED로 남고 고객은 성공을 받으며, 실패는 orderId·eventId와 함께 기록된다.")
    @Test
    void sendFailure_doesNotChangeOrderOrResponse() throws Exception {
        // arrange
        Fixture fixture = pending();
        publisher.mode(Mode.FAIL);
        double publishFailedBefore = unpublished(OrderConfirmedUnpublished.Reason.PUBLISH_FAILED);

        // act
        OrderInfo response = confirmBySuccess(fixture);
        drainPublication();

        // assert
        assertAll(
            () -> assertThat(response.status()).isEqualTo(OrderStatus.CONFIRMED),
            () -> assertThat(order(fixture).getStatus()).isEqualTo(OrderStatus.CONFIRMED),
            () -> assertReservationConsumed(fixture),
            () -> assertThat(publisher.callsFor(fixture.orderId())).isEqualTo(1),
            () -> assertThat(logs.list)
                .filteredOn(event -> event.getLevel() == Level.ERROR)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> assertThat(message)
                    .contains("미발행", "orderId=" + fixture.orderId(), "eventId=" + fixture.eventId())),
            () -> assertThat(unpublished(OrderConfirmedUnpublished.Reason.PUBLISH_FAILED) - publishFailedBefore).isEqualTo(1.0),
            () -> assertThat(recordsOf(readAll(), fixture)).isEmpty()
        );
    }

    @DisplayName("등록 실패: 확정 트랜잭션 안에서 발행 등록이 실패해도 확정은 commit되고 고객은 성공을 받으며, "
        + "미발행(register_failed)으로 센다. (D-26)")
    @Test
    void registerFailure_keepsConfirmation_andCounts() throws Exception {
        // arrange
        Fixture fixture = pending();
        doThrow(new IllegalStateException("주입된 등록 실패"))
            .when(orderConfirmedPublication).register(argThat(payload -> payload != null && fixture.orderId().equals(payload.orderId())));
        double registerFailedBefore = unpublished(OrderConfirmedUnpublished.Reason.REGISTER_FAILED);

        // act
        OrderInfo response = confirmBySuccess(fixture);
        drainPublication();

        // assert
        assertAll(
            () -> assertThat(response.status()).isEqualTo(OrderStatus.CONFIRMED),
            () -> assertThat(order(fixture).getStatus()).isEqualTo(OrderStatus.CONFIRMED),
            () -> assertThat(attemptStatus(fixture)).isEqualTo(PaymentAttemptStatus.SUCCESS),
            () -> assertReservationConsumed(fixture),
            () -> assertThat(unpublished(OrderConfirmedUnpublished.Reason.REGISTER_FAILED) - registerFailedBefore).isEqualTo(1.0),
            () -> assertThat(publisher.callsFor(fixture.orderId())).isZero(),
            () -> assertThat(logs.list)
                .filteredOn(event -> event.getLevel() == Level.ERROR)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> assertThat(message)
                    .contains("미발행", "orderId=" + fixture.orderId(), "eventId=" + fixture.eventId())),
            () -> assertThat(recordsOf(readAll(), fixture)).isEmpty()
        );
    }

    @DisplayName("발행 없음: 처리중(PENDING)·응답 유실(UNKNOWN)·실패(FAILED)는 아무것도 발행하지 않는다.")
    @Test
    void nonSuccessResults_publishNothing() throws Exception {
        // arrange & act
        Fixture pending = pending();

        Fixture failed = pending();
        stubSingle(failed, "FAILED");
        OrderInfo failedResponse = orderFacade.reconcilePayment(failed.orderId(), failed.userId());

        // UNKNOWN은 마지막에 만든다 — 늦게 응답하는 create stub이 남아 있으면 뒤의 확정도 UNKNOWN이 된다.
        Fixture unknown = unknown();

        drainPublication();

        // assert
        assertAll(
            () -> assertThat(attemptStatus(pending)).isEqualTo(PaymentAttemptStatus.PENDING),
            () -> assertThat(failedResponse.status()).isEqualTo(OrderStatus.PAYMENT_FAILED),
            () -> assertThat(attemptStatus(unknown)).isEqualTo(PaymentAttemptStatus.UNKNOWN),
            () -> assertThat(publisher.callsFor(pending.orderId())).isZero(),
            () -> assertThat(publisher.callsFor(failed.orderId())).isZero(),
            () -> assertThat(publisher.callsFor(unknown.orderId())).isZero()
        );
    }

    /** 생성 응답을 잃고 바로 재조회도 0건이라 UNKNOWN이 된 결제. 늦게 기록되는 생성 요청을 기다려 다음 테스트로 넘기지 않는다. */
    private Fixture unknown() throws InterruptedException {
        Fixture fixture = draft();
        int before = pg.findAll(postRequestedFor(urlEqualTo(TestPg.PAYMENTS_PATH))).size();
        pg.stubFor(post(urlEqualTo(TestPg.PAYMENTS_PATH)).willReturn(aResponse().withStatus(202).withFixedDelay(1_000)));
        pg.stubFor(get(urlPathEqualTo(TestPg.PAYMENTS_PATH))
            .withQueryParam("orderId", equalTo(fixture.providerOrderId()))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("[]")));
        orderFacade.confirmOrder(fixture.orderId(), fixture.userId(), command(fixture));
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (pg.findAll(postRequestedFor(urlEqualTo(TestPg.PAYMENTS_PATH))).size() <= before && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        return fixture;
    }

    /**
     * 발행 없음(롤백): SUCCESS 반영을 바깥 트랜잭션 안에서 하고 그 트랜잭션을 롤백한다. apply는 바깥 트랜잭션에 합류하므로
     * 확정과 afterCommit 등록까지는 실제로 일어나지만(등록 수가 늘어난 것을 확인한다), commit이 없어 발행되지 않는다.
     * 운영 경로에서 확정 트랜잭션이 commit 단계에서 실패하는 경우를 같은 동기화 규칙으로 보인다.
     */
    @DisplayName("발행 없음: 확정 트랜잭션이 롤백되면 확정은 남지 않고 아무것도 발행하지 않는다. (INV-601)")
    @Test
    void rolledBackConfirmation_publishesNothing() throws Exception {
        // arrange
        Fixture fixture = pending();
        int[] registered = new int[2];

        // act
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            registered[0] = TransactionSynchronizationManager.getSynchronizations().size();
            ApplyResult result = paymentResultApplier.apply(fixture.orderId(), success(fixture));
            assertThat(result).isEqualTo(ApplyResult.SUCCEEDED);
            registered[1] = TransactionSynchronizationManager.getSynchronizations().size();
            status.setRollbackOnly();
        });
        drainPublication();

        // assert
        assertAll(
            () -> assertThat(registered[1]).as("afterCommit 등록").isGreaterThan(registered[0]),
            () -> assertThat(order(fixture).getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING),
            () -> assertThat(attemptStatus(fixture)).isEqualTo(PaymentAttemptStatus.PENDING),
            () -> assertThat(publisher.callsFor(fixture.orderId())).isZero()
        );
    }

    @DisplayName("중복 결과 적용: 같은 PG 성공을 여러 번 반영해도 발행은 한 번이고 재고도 한 번만 줄어 있다.")
    @Test
    void repeatedSuccess_publishesOnce() throws Exception {
        // arrange
        Fixture fixture = pending();
        confirmBySuccess(fixture);

        // act — 같은 결과가 다른 입구(콜백 워커·회복 작업)로 다시 들어온 것과 같다. 모두 apply를 지난다.
        ApplyResult second = paymentResultApplier.apply(fixture.orderId(), success(fixture));
        ApplyResult third = paymentResultApplier.apply(fixture.orderId(), success(fixture));
        OrderInfo again = orderFacade.reconcilePayment(fixture.orderId(), fixture.userId());
        drainPublication();

        // assert
        awaitRecord(fixture);
        assertAll(
            () -> assertThat(second).isEqualTo(ApplyResult.NO_EFFECT),
            () -> assertThat(third).isEqualTo(ApplyResult.NO_EFFECT),
            () -> assertThat(again.status()).isEqualTo(OrderStatus.CONFIRMED),
            () -> assertThat(publisher.callsFor(fixture.orderId())).isEqualTo(1),
            () -> assertThat(recordsOf(readAll(), fixture)).hasSize(1),
            () -> assertReservationConsumed(fixture)
        );
    }

    // ---------------------------------------------------------------- 발행 이음매의 스위치

    enum Mode { PASS_THROUGH, HALT_BEFORE_SEND, FAIL }

    @TestConfiguration
    static class SwitchConfig {
        @Bean
        @Primary
        SwitchablePublisher switchablePublisher(KafkaOrderConfirmedPublisher kafkaOrderConfirmedPublisher) {
            return new SwitchablePublisher(kafkaOrderConfirmedPublisher);
        }
    }

    /**
     * 진짜 Kafka 구현을 감싼다. 받은 payload를 모두 기록하고, 모드에 따라 그대로 넘기거나(정상), 넘기기 직전에 멈추거나
     * (누락 구간 — 프로세스가 commit과 send 사이에서 죽은 것과 같다), 실패를 던진다(전송 실패).
     * 음수 orderId는 {@link #drainPublication()}의 표지라 Kafka로 보내지 않는다.
     */
    static class SwitchablePublisher implements OrderConfirmedPublisher {

        private final OrderConfirmedPublisher delegate;
        private final List<OrderConfirmedPayload> calls = new CopyOnWriteArrayList<>();
        private final List<Long> halted = new CopyOnWriteArrayList<>();
        private final Map<Long, CountDownLatch> sentinels = new ConcurrentHashMap<>();
        private volatile Mode mode = Mode.PASS_THROUGH;

        SwitchablePublisher(OrderConfirmedPublisher delegate) {
            this.delegate = delegate;
        }

        void reset() {
            mode = Mode.PASS_THROUGH;
            calls.clear();
            halted.clear();
            sentinels.clear();
        }

        void mode(Mode mode) {
            this.mode = mode;
        }

        CountDownLatch expectSentinel(long sentinelId) {
            CountDownLatch latch = new CountDownLatch(1);
            sentinels.put(sentinelId, latch);
            return latch;
        }

        long callsFor(Long orderId) {
            return calls.stream().filter(payload -> payload.orderId().equals(orderId)).count();
        }

        OrderConfirmedPayload payloadOf(Long orderId) {
            return calls.stream().filter(payload -> payload.orderId().equals(orderId)).findFirst().orElseThrow();
        }

        List<Long> haltedOrderIds() {
            return List.copyOf(halted);
        }

        @Override
        public void publish(OrderConfirmedPayload payload) {
            if (payload.orderId() < 0) {
                CountDownLatch latch = sentinels.get(payload.orderId());
                if (latch != null) {
                    latch.countDown();
                }
                return;
            }
            calls.add(payload);
            switch (mode) {
                case PASS_THROUGH -> delegate.publish(payload);
                case HALT_BEFORE_SEND -> halted.add(payload.orderId());
                case FAIL -> throw new IllegalStateException("주입된 전송 실패");
            }
        }
    }
}
