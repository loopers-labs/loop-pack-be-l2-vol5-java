package com.loopers.application.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderEventOutboxModel;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.OutboxStatus;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.StockLedgerType;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.order.OrderConfirmedJsonSerializer;
import com.loopers.support.FakePaymentGateway;
import com.loopers.support.KafkaTestRecords;
import com.loopers.support.fixture.Fixtures;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.loopers.support.FakePaymentGateway.failed;
import static com.loopers.support.FakePaymentGateway.pending;
import static com.loopers.support.FakePaymentGateway.success;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 주문 확정과 OrderConfirmed Outbox 의 로컬 원자성. relay 없이 실제 MySQL 만 본다 (broker 는 직접 발행이 없음을 확인하는 데만).
 * 고정 clock: 2026-09-07T01:00:00.123456789Z → 확정 시각 2026-09-07T01:00:00.123456Z. 품목: 상품 A 2개(1,000원) + 상품 B 1개(500원), 각 재고 5.
 * 실행: ./gradlew :apps:commerce-api:test --tests '*OutboxAtomicityTest' (Docker 필요, Testcontainers).
 */
@SpringBootTest(properties = "kafka.topics.order-events.name=" + OutboxAtomicityTest.TOPIC)
@Import(OutboxAtomicityTest.TestConfig.class)
class OutboxAtomicityTest {
    static final String TOPIC = "order-events-outbox-atomicity-test";
    private static final Instant FIXED_NOW = Instant.parse("2026-09-07T01:00:00.123456789Z");
    private static final String CONFIRMED_AT = "2026-09-07T01:00:00.123456Z";

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED_NOW, ZoneId.of("Asia/Seoul"));
        }

        @Bean
        @Primary
        FailingSerializer failingSerializer(OrderConfirmedJsonSerializer delegate) {
            return new FailingSerializer(delegate);
        }
    }

    /** 실제 직렬화 앞에서 실패를 주입한다 (Outbox insert 전 실패). */
    static class FailingSerializer implements OrderConfirmedPayloadSerializer {
        private final OrderConfirmedPayloadSerializer delegate;
        private volatile boolean fail;

        FailingSerializer(OrderConfirmedPayloadSerializer delegate) {
            this.delegate = delegate;
        }

        @Override
        public String serialize(OrderConfirmedEvent event) {
            if (fail) {
                throw new IllegalStateException("주입된 실패: OrderConfirmed 직렬화 (Outbox insert 전)");
            }
            return delegate.serialize(event);
        }
    }

    @Autowired
    private OrderFacade orderFacade;
    @Autowired
    private OrderPaymentProcessor orderPaymentProcessor;
    @Autowired
    private OrderService orderService;
    @Autowired
    private OrderConfirmedOutboxFactory outboxFactory;
    @Autowired
    private FailingSerializer serializer;
    @Autowired
    private FakePaymentGateway fakePg;
    @Autowired
    private TransactionTemplate transactionTemplate;
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
        serializer.fail = false;
        fakePg.reset();
        databaseCleanUp.truncateAllTables();
    }

    private OrderInfo draftOrder() {
        return orderFacade.createOrder(user.getId(), List.of(new OrderItemCommand(productB.getId(), 1), new OrderItemCommand(productA.getId(), 2)));
    }

    private OrderInfo confirm(Long orderId) {
        return orderFacade.confirmOrder(user.getId(), orderId, "attempt-" + orderId, "SAMSUNG", "1234-5678-9814-1451");
    }

    /** PG 가 PENDING 으로 접수한 결제 대기 주문. 재고는 예약됐고 PG 결제 요청(create)은 1회. */
    private OrderInfo awaitingOrder() {
        OrderInfo order = draftOrder();
        fakePg.willRespond(pending(FakePaymentGateway.transactionKeyOf(order.id())));
        confirm(order.id());
        return order;
    }

    /** 결제 대기 그대로: 상태·예약(RESERVE 만, RELEASE 없음)·Outbox 0. */
    private void assertStillAwaiting(OrderInfo order) {
        assertThat(fixtures.reloadOrder(order.id()).getStatus()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        assertThat(fixtures.stockOf(productA.getId())).isEqualTo(3);
        assertThat(fixtures.stockOf(productB.getId())).isEqualTo(4);
        assertThat(fixtures.ledgerOf(productA.getId())).extracting("type").containsExactly(StockLedgerType.ADJUSTMENT, StockLedgerType.RESERVE);
        assertThat(fixtures.ledgerOf(productB.getId())).extracting("type").containsExactly(StockLedgerType.ADJUSTMENT, StockLedgerType.RESERVE);
        assertThat(fixtures.outboxOf(order.id())).isEmpty();
    }

    @DisplayName("검증된 PG SUCCESS 로 처음 확정하면 같은 트랜잭션에 OrderConfirmed Outbox 한 행(PENDING)이 남는다. key·payload 는 저장된 확정 사실과 같고, broker 로 직접 보내지 않는다.")
    @Test
    void recordsOutbox_inConfirmTransaction() throws Exception {
        OrderInfo order = draftOrder();
        Map<TopicPartition, Long> endBefore = KafkaTestRecords.endOffsets(TOPIC);

        OrderInfo confirmed = confirm(order.id());

        assertThat(confirmed.status()).isEqualTo(OrderStatus.CONFIRMED.name());
        OrderModel stored = fixtures.reloadOrder(order.id());
        assertThat(fixtures.stockOf(productA.getId())).isEqualTo(3);
        assertThat(fixtures.stockOf(productB.getId())).isEqualTo(4);

        List<OrderEventOutboxModel> outbox = fixtures.outboxOf(order.id());
        assertThat(outbox).hasSize(1);
        OrderEventOutboxModel row = outbox.get(0);
        assertThat(row.getEventId()).isEqualTo("order-confirmed:v1:" + order.id());
        assertThat(row.getEventType()).isEqualTo("OrderConfirmed");
        assertThat(row.getSchemaVersion()).isEqualTo(1);
        assertThat(row.getMessageKey()).isEqualTo(String.valueOf(order.id()));
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(row.getAttemptCount()).isZero();
        assertThat(row.getFailureCount()).isZero();
        assertThat(row.getNextAttemptAt().toInstant()).isEqualTo(stored.getConfirmedAt().toInstant());
        assertThat(row.getLastError()).isNull();
        assertThat(row.getPublishedAt()).isNull();
        assertThat(row.getCreatedAt()).isNotNull();

        JsonNode payload = objectMapper.readTree(row.getPayload());
        assertThat(payload.fieldNames()).toIterable().containsExactlyInAnyOrder("eventType", "schemaVersion", "eventId", "orderId", "confirmedAt", "items");
        assertThat(payload.get("eventType").asText()).isEqualTo("OrderConfirmed");
        assertThat(payload.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(payload.get("eventId").asText()).isEqualTo(row.getEventId());
        assertThat(payload.get("orderId").asLong()).isEqualTo(order.id());
        assertThat(payload.get("confirmedAt").asText()).isEqualTo(CONFIRMED_AT).isEqualTo(stored.getConfirmedAt().toInstant().toString());
        assertThat(payload.get("items")).extracting(item -> item.get("productId").asLong(), item -> item.get("quantity").asInt())
            .containsExactly(tuple(productA.getId(), 2), tuple(productB.getId(), 1));

        assertThat(KafkaTestRecords.endOffsets(TOPIC)).isEqualTo(endBefore);
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

    @DisplayName("확정되지 않은 결과(대기·UNKNOWN·실패·금액 불일치)에서는 Outbox 를 만들지 않는다.")
    @ParameterizedTest(name = "{0}")
    @MethodSource("notConfirmedResults")
    void doesNotRecordOutbox_whenNotConfirmed(String name, PaymentGatewayResult result) {
        OrderInfo order = draftOrder();
        fakePg.willRespond(result);

        OrderInfo after = confirm(order.id());

        assertThat(after.status()).isNotEqualTo(OrderStatus.CONFIRMED.name());
        assertThat(fixtures.outboxOf(order.id())).isEmpty();
    }

    @DisplayName("callback 이 먼저 확정한 뒤 reconcile·같은 callback·같은 결과 반영이 이어져도 확정·예약·Outbox 는 각각 한 번이고 PG 결제 요청은 늘지 않는다.")
    @Test
    void recordsOutboxOnce_whenResultAppliedRepeatedly() {
        OrderInfo order = awaitingOrder();
        String tx = FakePaymentGateway.transactionKeyOf(order.id());
        fakePg.willFind(success(tx, order.totalAmount()));

        orderFacade.applyPaymentCallback(PgOrderId.of(order.id()), success(tx, order.totalAmount()));
        OrderEventOutboxModel first = fixtures.outboxOf(order.id()).get(0);
        orderFacade.resolveAwaitingPayment(user.getId(), order.id());
        orderFacade.applyPaymentCallback(PgOrderId.of(order.id()), success(tx, order.totalAmount()));
        orderPaymentProcessor.applyResult(order.id(), success(tx, order.totalAmount()));

        assertThat(fixtures.reloadOrder(order.id()).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(fixtures.outboxOf(order.id())).extracting(OrderEventOutboxModel::getId).containsExactly(first.getId());
        assertThat(fixtures.ledgerOf(productA.getId())).extracting("type").containsExactly(StockLedgerType.ADJUSTMENT, StockLedgerType.RESERVE);
        assertThat(fixtures.stockOf(productA.getId())).isEqualTo(3);
        assertThat(fakePg.requests()).hasSize(1);
    }

    @DisplayName("Outbox insert 전(직렬화)에 실패하면 확정 트랜잭션 전체가 rollback 된다. 기존 결제 대기 주문·예약은 그대로이고, 같은 거래 재조회로 다시 반영하면 PG 결제 요청 없이 확정·Outbox 가 한 번 생긴다.")
    @Test
    void rollsBackConfirm_whenFailingBeforeOutboxInsert() {
        OrderInfo order = awaitingOrder();
        fakePg.willFind(success(FakePaymentGateway.transactionKeyOf(order.id()), order.totalAmount()));
        serializer.fail = true;

        assertThatThrownBy(() -> orderFacade.resolveAwaitingPayment(user.getId(), order.id()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("직렬화");

        assertStillAwaiting(order);
        assertThat(fakePg.requests()).hasSize(1);

        serializer.fail = false;
        OrderInfo retried = orderFacade.resolveAwaitingPayment(user.getId(), order.id());

        assertThat(retried.status()).isEqualTo(OrderStatus.CONFIRMED.name());
        assertThat(fixtures.outboxOf(order.id())).hasSize(1);
        assertThat(fixtures.ledgerOf(productA.getId())).extracting("type").containsExactly(StockLedgerType.ADJUSTMENT, StockLedgerType.RESERVE);
        assertThat(fakePg.requests()).hasSize(1);
        assertThat(fakePg.finds()).containsExactly(order.id(), order.id());
    }

    @DisplayName("Outbox insert 뒤 commit 전에 실패하면 insert 한 행까지 rollback 된다. 기존 결제 대기 주문·예약은 그대로이고, 다시 반영하면 PG 결제 요청 없이 확정·Outbox 가 한 번 생긴다.")
    @Test
    void rollsBackConfirm_whenFailingAfterOutboxInsert() {
        OrderInfo order = awaitingOrder();
        String tx = FakePaymentGateway.transactionKeyOf(order.id());

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            OrderInfo inTx = orderPaymentProcessor.applyResult(order.id(), success(tx, order.totalAmount()));
            assertThat(inTx.status()).isEqualTo(OrderStatus.CONFIRMED.name());
            assertThat(fixtures.outboxOf(order.id())).hasSize(1);
            throw new IllegalStateException("주입된 실패: Outbox insert 뒤, commit 전");
        })).hasMessageContaining("commit 전");

        assertStillAwaiting(order);
        assertThat(fakePg.requests()).hasSize(1);

        fakePg.willFind(success(tx, order.totalAmount()));
        OrderInfo retried = orderFacade.resolveAwaitingPayment(user.getId(), order.id());

        assertThat(retried.status()).isEqualTo(OrderStatus.CONFIRMED.name());
        assertThat(fixtures.outboxOf(order.id())).hasSize(1);
        assertThat(fakePg.requests()).hasSize(1);
    }

    @DisplayName("같은 eventId 의 Outbox 두 번째 행은 DB 유일 제약이 막는다.")
    @Test
    void rejectsSecondOutboxRow_withSameEventId() {
        OrderInfo order = draftOrder();
        confirm(order.id());

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
            orderService.appendOutbox(outboxFactory.create(orderService.get(order.id())))))
            .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(fixtures.outboxOf(order.id())).hasSize(1);
    }
}
