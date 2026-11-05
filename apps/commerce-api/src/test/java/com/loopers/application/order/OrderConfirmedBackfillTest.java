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
import com.loopers.domain.user.UserModel;
import com.loopers.support.FakePaymentGateway;
import com.loopers.support.error.ErrorType;
import com.loopers.support.fixture.Fixtures;
import com.loopers.support.w6.W6DirectPublicationConfig;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static com.loopers.support.ErrorAssertions.assertThrowsErrorType;
import static com.loopers.support.FakePaymentGateway.pending;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * W6 누락 확정의 Outbox 복구 (OrderConfirmedBackfill). 실제 MySQL.
 * 누락 상태는 보존한 W6 경로(W6DirectPublicationConfig)로 실제 확정해 만든다: CONFIRMED·예약 소비는 있고 Outbox 는 없다. 행을 지워 꾸미지 않는다.
 * 실행: ./gradlew :apps:commerce-api:test --tests '*OrderConfirmedBackfillTest' (Docker 필요, Testcontainers).
 */
@SpringBootTest(properties = "kafka.topics.order-events.name=order-events-backfill-test")
@Import(W6DirectPublicationConfig.class)
class OrderConfirmedBackfillTest {

    @Autowired
    private OrderConfirmedBackfill backfill;
    @Autowired
    private OrderFacade orderFacade;
    @Autowired
    private OrderService orderService;
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
        fakePg.reset();
        databaseCleanUp.truncateAllTables();
    }

    private OrderInfo draftOrder() {
        return orderFacade.createOrder(user.getId(), List.of(new OrderItemCommand(productA.getId(), 2), new OrderItemCommand(productB.getId(), 1)));
    }

    private OrderInfo confirm(Long orderId) {
        return orderFacade.confirmOrder(user.getId(), orderId, "attempt-" + orderId, "SAMSUNG", "1234-5678-9814-1451");
    }

    /** W6 경로로 확정된 주문: CONFIRMED·예약 소비, Outbox 없음. */
    private OrderModel confirmedWithoutOutbox() {
        OrderInfo order = draftOrder();
        confirm(order.id());
        OrderModel stored = fixtures.reloadOrder(order.id());
        assertThat(stored.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(fixtures.outboxOf(order.id())).isEmpty();
        return stored;
    }

    @DisplayName("Outbox 가 없는 확정 주문은 저장된 eventId·confirmedAt·품목 그대로 PENDING 한 행이 생긴다. PG 를 부르지 않고 주문·재고는 그대로다. 두 번째 복구는 아무것도 늘리지 않는다.")
    @Test
    void recordsMissingOutbox_fromStoredFacts_once() throws Exception {
        OrderModel before = confirmedWithoutOutbox();
        int requests = fakePg.requests().size();
        int finds = fakePg.finds().size();
        int ledgerA = fixtures.ledgerOf(productA.getId()).size();

        assertThat(backfill.backfill(before.getId())).isEqualTo(OrderConfirmedBackfill.Result.RECORDED);

        List<OrderEventOutboxModel> outbox = fixtures.outboxOf(before.getId());
        assertThat(outbox).hasSize(1);
        OrderEventOutboxModel row = outbox.get(0);
        assertThat(row.getEventId()).isEqualTo(OrderConfirmedEvent.eventIdOf(before.getId()));
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(row.getMessageKey()).isEqualTo(String.valueOf(before.getId()));
        JsonNode payload = objectMapper.readTree(row.getPayload());
        assertThat(payload.get("confirmedAt").asText()).isEqualTo(before.getConfirmedAt().toInstant().toString());
        assertThat(payload.get("items")).extracting(item -> item.get("productId").asLong(), item -> item.get("quantity").asInt())
            .containsExactly(tuple(productA.getId(), 2), tuple(productB.getId(), 1));

        OrderModel after = fixtures.reloadOrder(before.getId());
        assertThat(after.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(after.getConfirmedAt()).isEqualTo(before.getConfirmedAt());
        assertThat(fixtures.ledgerOf(productA.getId())).hasSize(ledgerA);
        assertThat(fixtures.stockOf(productA.getId())).isEqualTo(3);

        assertThat(backfill.backfill(before.getId())).isEqualTo(OrderConfirmedBackfill.Result.ALREADY_RECORDED);
        assertThat(fixtures.outboxOf(before.getId())).extracting(OrderEventOutboxModel::getId).containsExactly(row.getId());
        assertThat(fakePg.requests()).hasSize(requests);
        assertThat(fakePg.finds()).hasSize(finds);
    }

    @DisplayName("같은 eventId 의 Outbox 가 저장된 확정 사실과 다르면 고치지 않고 예외로 멈춘다.")
    @Test
    void rejects_whenExistingOutboxDiffers() {
        OrderModel order = confirmedWithoutOutbox();
        transactionTemplate.executeWithoutResult(status -> orderService.appendOutbox(OrderEventOutboxModel.pending(
            OrderConfirmedEvent.eventIdOf(order.getId()), order.getId(), OrderConfirmedEvent.EVENT_TYPE, OrderConfirmedEvent.SCHEMA_VERSION,
            String.valueOf(order.getId()), "{\"eventId\":\"다른 내용\"}", order.getConfirmedAt())));

        assertThatThrownBy(() -> backfill.backfill(order.getId()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("다르다");

        assertThat(fixtures.outboxOf(order.getId())).extracting(OrderEventOutboxModel::getPayload).containsExactly("{\"eventId\":\"다른 내용\"}");
    }

    @DisplayName("확정되지 않은 주문(DRAFT·결제 대기)은 확정 사실이 없어 Outbox 를 만들지 않고 예외로 멈춘다.")
    @Test
    void rejects_whenOrderNotConfirmed() {
        OrderInfo draft = draftOrder();
        OrderInfo awaiting = draftOrder();
        fakePg.willRespond(pending(FakePaymentGateway.transactionKeyOf(awaiting.id())));
        confirm(awaiting.id());

        assertThatThrownBy(() -> backfill.backfill(draft.id())).isInstanceOf(IllegalStateException.class).hasMessageContaining("확정 사실이 없어");
        assertThatThrownBy(() -> backfill.backfill(awaiting.id())).isInstanceOf(IllegalStateException.class).hasMessageContaining("확정 사실이 없어");

        assertThat(fixtures.outboxOf(draft.id())).isEmpty();
        assertThat(fixtures.outboxOf(awaiting.id())).isEmpty();
        assertThat(fixtures.reloadOrder(awaiting.id()).getStatus()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
    }

    @DisplayName("없는 주문은 ORDER_NOT_FOUND.")
    @Test
    void rejects_whenOrderMissing() {
        assertThrowsErrorType(() -> backfill.backfill(999_999L), ErrorType.ORDER_NOT_FOUND);
    }
}
