package com.loopers.application.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.StockLedgerType;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.order.KafkaOrderConfirmedPublisher;
import com.loopers.support.FakePaymentGateway;
import com.loopers.support.KafkaTestRecords;
import com.loopers.support.fixture.Fixtures;
import com.loopers.utils.DatabaseCleanUp;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.loopers.support.FakePaymentGateway.failed;
import static com.loopers.support.FakePaymentGateway.pending;
import static com.loopers.support.FakePaymentGateway.success;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * W6 주문 확정 → OrderConfirmed 직접 발행 (commit 뒤). 실제 MySQL·Kafka broker, PG 는 FakePaymentGateway.
 * 이 클래스만의 topic(order-events-publication-test)을 써서 다른 테스트의 발행과 섞이지 않는다.
 * 고정 clock: 2026-09-07T01:00:00.123456789Z → 확정 시각은 마이크로초로 잘려 2026-09-07T01:00:00.123456Z.
 * 고정 품목: 상품 A 2개(1,000원) + 상품 B 1개(500원), 각 재고 5.
 * 실행: ./gradlew :apps:commerce-api:test --tests '*OrderConfirmedPublicationTest' (Docker 필요, Testcontainers).
 */
@SpringBootTest(properties = "kafka.topics.order-events.name=" + OrderConfirmedPublicationTest.TOPIC)
@Import(OrderConfirmedPublicationTest.TestPublisherConfig.class)
class OrderConfirmedPublicationTest {
    static final String TOPIC = "order-events-publication-test";
    private static final Instant FIXED_NOW = Instant.parse("2026-09-07T01:00:00.123456789Z");
    private static final String CONFIRMED_AT = "2026-09-07T01:00:00.123456Z";
    private static final Logger log = LoggerFactory.getLogger(OrderConfirmedPublicationTest.class);

    @TestConfiguration
    static class TestPublisherConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED_NOW, ZoneId.of("Asia/Seoul"));
        }

        @Bean
        @Primary
        RecordingPublisher recordingPublisher(KafkaOrderConfirmedPublisher kafkaPublisher) {
            return new RecordingPublisher(kafkaPublisher);
        }
    }

    /** 실제 Kafka 발행 앞에서 호출을 기록한다. failBeforeSend 면 send 를 부르기 직전에 멈춘다 (commit 뒤·전송 전 중단의 통제된 hook). */
    static class RecordingPublisher implements OrderConfirmedPublisher {
        private final OrderConfirmedPublisher delegate;
        private final List<OrderConfirmedEvent> events = new CopyOnWriteArrayList<>();
        private final List<CompletableFuture<Published>> results = new CopyOnWriteArrayList<>();
        private volatile boolean failBeforeSend;

        RecordingPublisher(OrderConfirmedPublisher delegate) {
            this.delegate = delegate;
        }

        @Override
        public CompletableFuture<Published> publish(OrderConfirmedEvent event) {
            events.add(event);
            if (failBeforeSend) {
                throw new IllegalStateException("주입된 중단: 주문 확정 commit 뒤, Kafka send 직전");
            }
            CompletableFuture<Published> result = delegate.publish(event);
            results.add(result);
            return result;
        }

        void reset() {
            events.clear();
            results.clear();
            failBeforeSend = false;
        }
    }

    @Autowired
    private OrderFacade orderFacade;
    @Autowired
    private OrderPaymentProcessor orderPaymentProcessor;
    @Autowired
    private RecordingPublisher publisher;
    @Autowired
    private FakePaymentGateway fakePg;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private MeterRegistry meterRegistry;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private UserModel user;
    private ProductModel productA;
    private ProductModel productB;

    @BeforeEach
    void setUp() {
        fixtures.catalogRevision();
        user = fixtures.user();
        BrandModel brand = fixtures.brand("브랜드");
        productA = fixtures.product(brand.getId(), "상품 A", 1_000L, 5);
        productB = fixtures.product(brand.getId(), "상품 B", 500L, 5);
    }

    @AfterEach
    void tearDown() {
        publisher.reset();
        fakePg.reset();
        databaseCleanUp.truncateAllTables();
    }

    /** DRAFT 주문: 상품 B 1개 + 상품 A 2개 (요청 순서와 무관하게 이벤트 items 는 productId 오름차순). */
    private OrderInfo draftOrder() {
        return orderFacade.createOrder(user.getId(), List.of(new OrderItemCommand(productB.getId(), 1), new OrderItemCommand(productA.getId(), 2)));
    }

    private OrderInfo confirm(Long orderId) {
        return orderFacade.confirmOrder(user.getId(), orderId, "attempt-" + orderId, "SAMSUNG", "1234-5678-9814-1451");
    }

    /** PG 가 PENDING 으로 접수한 결제 대기 주문. */
    private OrderInfo awaitingOrder() {
        OrderInfo order = draftOrder();
        fakePg.willRespond(pending(FakePaymentGateway.transactionKeyOf(order.id())));
        confirm(order.id());
        fakePg.reset();
        return order;
    }

    private OrderConfirmedPublisher.Published awaitPublished(int index) throws Exception {
        return publisher.results.get(index).get(10, TimeUnit.SECONDS);
    }

    private double publishCount(String result) {
        return meterRegistry.counter("order.confirmed.publish", "result", result).count();
    }

    @DisplayName("[W6 정상 사례] 검증된 PG SUCCESS 로 처음 확정하면 commit 뒤 OrderConfirmed 하나가 order-events 에 기록된다. key·payload 는 저장된 확정 주문과 같다.")
    @Test
    void publishesOrderConfirmed_matchingStoredOrder() throws Exception {
        OrderInfo order = draftOrder();
        double successBefore = publishCount("success");

        OrderInfo confirmed = confirm(order.id());

        assertThat(confirmed.status()).isEqualTo(OrderStatus.CONFIRMED.name());
        assertThat(publisher.events).hasSize(1);
        OrderConfirmedPublisher.Published published = awaitPublished(0);
        assertThat(published.topic()).isEqualTo(TOPIC);
        assertThat(publishCount("success")).isEqualTo(successBefore + 1);

        OrderModel stored = fixtures.reloadOrder(order.id());
        ConsumerRecord<String, String> record = KafkaTestRecords.readAt(published.topic(), published.partition(), published.offset());
        JsonNode value = objectMapper.readTree(record.value());
        assertThat(record.key()).isEqualTo(String.valueOf(order.id()));
        assertThat(value.get("eventType").asText()).isEqualTo("OrderConfirmed");
        assertThat(value.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(value.get("eventId").asText()).isEqualTo("order-confirmed:v1:" + order.id());
        assertThat(value.get("orderId").asLong()).isEqualTo(order.id());
        assertThat(value.get("confirmedAt").asText()).isEqualTo(CONFIRMED_AT).isEqualTo(stored.getConfirmedAt().toInstant().toString());
        assertThat(value.get("items")).extracting(item -> item.get("productId").asLong(), item -> item.get("quantity").asInt())
            .containsExactly(tuple(productA.getId(), 2), tuple(productB.getId(), 1));
        assertThat(stored.getItems()).extracting("productId", "quantity")
            .containsExactlyInAnyOrder(tuple(productA.getId(), 2), tuple(productB.getId(), 1));
        assertThat(value.fieldNames()).toIterable().containsExactlyInAnyOrder("eventType", "schemaVersion", "eventId", "orderId", "confirmedAt", "items");

        log.info("[W6 관찰: 정상 발행] orderId={}, providerOrderId={}, transactionKey={}, eventId={}, confirmedAt={}, items={}, "
                + "order={}, stockA={}, stockB={}, broker={}:{}@{}",
            order.id(), PgOrderId.of(order.id()), stored.getTransactionKey(), value.get("eventId").asText(), value.get("confirmedAt").asText(),
            value.get("items"), stored.getStatus(), fixtures.stockOf(productA.getId()), fixtures.stockOf(productB.getId()),
            published.topic(), published.partition(), published.offset());
    }

    static Stream<Arguments> notConfirmedResults() {
        return Stream.of(
            Arguments.of("PENDING", pending("tx-pending")),
            Arguments.of("UNKNOWN", PaymentGatewayResult.unknown()),
            Arguments.of("FAILED", failed("tx-failed")),
            Arguments.of("NOT_SENT", PaymentGatewayResult.notSent()),
            Arguments.of("ERROR", PaymentGatewayResult.error()),
            Arguments.of("SUCCESS 금액 불일치", success("tx-amount", 1L))
        );
    }

    @DisplayName("확정되지 않은 결과(대기·UNKNOWN·실패·금액 불일치)에서는 OrderConfirmed 를 발행하지 않는다.")
    @ParameterizedTest(name = "{0}")
    @MethodSource("notConfirmedResults")
    void doesNotPublish_whenNotConfirmed(String name, PaymentGatewayResult result) {
        OrderInfo order = draftOrder();
        Map<TopicPartition, Long> endBefore = KafkaTestRecords.endOffsets(TOPIC);
        fakePg.willRespond(result);

        OrderInfo after = confirm(order.id());

        assertThat(after.status()).isNotEqualTo(OrderStatus.CONFIRMED.name());
        assertThat(publisher.events).isEmpty();
        assertThat(KafkaTestRecords.endOffsets(TOPIC)).isEqualTo(endBefore);
    }

    @DisplayName("최초 확정 트랜잭션이 rollback 되면 OrderConfirmed 를 발행하지 않는다. 주문은 결제 대기 그대로.")
    @Test
    void doesNotPublish_whenConfirmRolledBack() {
        OrderInfo order = awaitingOrder();

        transactionTemplate.executeWithoutResult(status -> {
            OrderInfo inTx = orderPaymentProcessor.applyResult(order.id(), success(FakePaymentGateway.transactionKeyOf(order.id()), order.totalAmount()));
            assertThat(inTx.status()).isEqualTo(OrderStatus.CONFIRMED.name());
            status.setRollbackOnly();
        });

        assertThat(fixtures.reloadOrder(order.id()).getStatus()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        assertThat(publisher.events).isEmpty();
    }

    @DisplayName("callback 이 먼저 확정하면 그때 한 번 발행하고, 뒤이은 reconcile·같은 callback·같은 결과 반영은 새 이벤트도 재고 차감도 만들지 않는다.")
    @Test
    void publishesOnce_whenResultAppliedRepeatedly() throws Exception {
        OrderInfo order = awaitingOrder();
        String tx = FakePaymentGateway.transactionKeyOf(order.id());
        fakePg.willFind(success(tx, order.totalAmount()));
        int stockA = fixtures.stockOf(productA.getId());
        int ledgerA = fixtures.ledgerOf(productA.getId()).size();

        orderFacade.applyPaymentCallback(PgOrderId.of(order.id()), success(tx, order.totalAmount()));
        OrderConfirmedPublisher.Published first = awaitPublished(0);
        orderFacade.resolveAwaitingPayment(user.getId(), order.id());
        orderFacade.applyPaymentCallback(PgOrderId.of(order.id()), success(tx, order.totalAmount()));
        orderPaymentProcessor.applyResult(order.id(), success(tx, order.totalAmount()));

        assertThat(fixtures.reloadOrder(order.id()).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(publisher.events).extracting(OrderConfirmedEvent::eventId).containsExactly("order-confirmed:v1:" + order.id());
        Map<TopicPartition, Long> end = KafkaTestRecords.endOffsets(TOPIC);
        assertThat(end.get(new TopicPartition(TOPIC, first.partition()))).isEqualTo(first.offset() + 1);
        assertThat(fixtures.stockOf(productA.getId())).isEqualTo(stockA);
        assertThat(fixtures.ledgerOf(productA.getId())).hasSize(ledgerA);
    }

    @DisplayName("[W6 누락 사례] 확정 commit 뒤 send 직전에 멈추면 주문 CONFIRMED·재고 예약은 남고 broker 에는 해당 이벤트가 없다. 확정 응답은 실패로 바뀌지 않는다.")
    @Test
    void confirmedOrderRemains_withoutBrokerRecord_whenStoppedBeforeSend() {
        OrderInfo order = draftOrder();
        Map<TopicPartition, Long> endBefore = KafkaTestRecords.endOffsets(TOPIC);
        publisher.failBeforeSend = true;

        OrderInfo confirmed = confirm(order.id());

        assertThat(confirmed.status()).isEqualTo(OrderStatus.CONFIRMED.name());
        OrderModel stored = fixtures.reloadOrder(order.id());
        assertThat(stored.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(stored.getConfirmedAt().toInstant().toString()).isEqualTo(CONFIRMED_AT);
        assertThat(fixtures.stockOf(productA.getId())).isEqualTo(3);
        assertThat(fixtures.stockOf(productB.getId())).isEqualTo(4);
        assertThat(fixtures.ledgerOf(productA.getId())).extracting("type").contains(StockLedgerType.RESERVE);
        assertThat(publisher.events).extracting(OrderConfirmedEvent::eventId).containsExactly("order-confirmed:v1:" + order.id());
        assertThat(publisher.results).isEmpty();
        assertThat(KafkaTestRecords.endOffsets(TOPIC)).isEqualTo(endBefore);

        OrderConfirmedEvent missed = publisher.events.get(0);
        log.info("[W6 관찰: 미발행] orderId={}, providerOrderId={}, transactionKey={}, eventId={}, confirmedAt={}, items={}, "
                + "order={}, stockA={}, stockB={}, brokerEndOffsets={}, 구매 활동=없음(broker record 없음)",
            order.id(), PgOrderId.of(order.id()), stored.getTransactionKey(), missed.eventId(), missed.confirmedAt(), missed.items(),
            stored.getStatus(), fixtures.stockOf(productA.getId()), fixtures.stockOf(productB.getId()), endBefore);
    }
}
