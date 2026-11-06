package com.loopers.application.order.facade;

import com.loopers.application.order.dto.OrderBackfillInfo;
import com.loopers.application.order.dto.OrderOutboxResumeInfo;
import com.loopers.application.order.facade.OrderPaymentFacade.ConfirmCommand;
import com.loopers.domain.brand.entity.Brand;
import com.loopers.domain.order.entity.Order;
import com.loopers.domain.order.entity.OrderConfirmedOutbox;
import com.loopers.domain.order.enums.OrderBackfillResult;
import com.loopers.domain.order.enums.OrderOutboxStatus;
import com.loopers.domain.order.enums.OrderStatus;
import com.loopers.domain.order.gateway.OrderConfirmedSender;
import com.loopers.domain.order.vo.OrderConfirmedSendResult;
import com.loopers.domain.order.vo.OrderLine;
import com.loopers.domain.product.entity.Product;
import com.loopers.support.kafka.OrderEventsTopicReader;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;

/** 로컬 재개 진입점의 세 동작을 실제 MySQL · broker 로 확인함 (7주차 설계 5.2, 6.2) */
@SpringBootTest
class OrderOutboxOperationsTest {

    private static final Long USER_ID = 1L;

    @Autowired
    private OrderOutboxOperations operations;

    @MockitoSpyBean
    private OrderConfirmedSender sender;

    @Autowired
    private OrderPaymentFacade orderPaymentFacade;

    @Autowired
    private OrderEventsTopicReader topicReader;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private Brand brand;

    @BeforeEach
    void setUp() {
        brand = transactionTemplate.execute(status -> {
            Brand saved = new Brand("브랜드", null);
            entityManager.persist(saved);
            return saved;
        });
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("BLOCKED 를 재개하면 같은 행이 PENDING 이 되고, 다음 회차에 같은 payload 로 발행된다.")
    @Test
    void resumesBlockedAndPublishesSameRow() {
        // arrange
        Order order = persistDraft(persistProduct(0L));
        confirm(order);
        doReturn(new OrderConfirmedSendResult.Permanent("RecordTooLargeException: 너무 큼")).when(sender).send(anyString(), anyString());
        operations.runOnce();
        OrderConfirmedOutbox blocked = outboxOf(order);
        Map<TopicPartition, Long> before = topicReader.endOffsets();

        // act
        OrderOutboxResumeInfo info = operations.resume(blocked.getEventId());
        reset(sender);
        operations.runOnce();

        // assert
        List<ConsumerRecord<String, String>> records = topicReader.recordsSince(before);
        OrderConfirmedOutbox published = outboxOf(order);
        assertAll(
            () -> assertThat(blocked.getStatus()).isEqualTo(OrderOutboxStatus.BLOCKED),
            () -> assertThat(info).isEqualTo(new OrderOutboxResumeInfo(blocked.getEventId(), OrderOutboxStatus.PENDING, true)),
            () -> assertThat(records).singleElement().extracting(ConsumerRecord::value).isEqualTo(blocked.getPayload()),
            () -> assertThat(published.getId()).isEqualTo(blocked.getId()),
            () -> assertThat(published.getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED),
            () -> assertThat(published.getAttemptCount()).isEqualTo(2)
        );
    }

    @DisplayName("BLOCKED 가 아닌 행을 재개하면 아무것도 바꾸지 않고 현재 상태를 알린다. 없는 eventId 는 오류다.")
    @Test
    void reportsCurrentStatus_whenNotBlocked() {
        // arrange
        Order order = persistDraft(persistProduct(0L));
        confirm(order);
        OrderConfirmedOutbox pending = outboxOf(order);

        // act
        OrderOutboxResumeInfo info = operations.resume(pending.getEventId());

        // assert
        assertAll(
            () -> assertThat(info).isEqualTo(new OrderOutboxResumeInfo(pending.getEventId(), OrderOutboxStatus.PENDING, false)),
            () -> assertThat(outboxOf(order).getNextAttemptAt()).isEqualTo(pending.getNextAttemptAt()),
            () -> assertThrows(IllegalArgumentException.class, () -> operations.resume("order-confirmed:v1:999999"))
        );
    }

    @DisplayName("backfill 은 명시한 주문만 주문별로 처리해, Outbox 없는 확정은 잇고 · 이미 있으면 그대로 · 미확정은 오류로 알리며 다시 해도 늘지 않는다. (D-112)")
    @Test
    void backfillsOnlyGivenOrders() {
        // arrange
        Product product = persistProduct(1_000L);
        Order withoutOutbox = persistDraft(product);
        confirmWithPointLikeBeforeOutbox(withoutOutbox);
        Order recorded = persistDraft(persistProduct(0L));
        confirm(recorded);
        Order draft = persistDraft(product);
        Order notGiven = persistDraft(product);
        confirmWithPointLikeBeforeOutbox(notGiven);
        String recordedPayload = outboxOf(recorded).getPayload();
        ZonedDateTime confirmedAt = reload(withoutOutbox).getConfirmedAt();
        int stock = stockOf(product);
        long payments = paymentCount();

        // act
        List<OrderBackfillInfo> first = operations.backfill(List.of(withoutOutbox.getId(), recorded.getId(), draft.getId()));
        List<OrderBackfillInfo> second = operations.backfill(List.of(withoutOutbox.getId(), recorded.getId()));

        // assert
        OrderConfirmedOutbox backfilled = outboxOf(withoutOutbox);
        Order reloaded = reload(withoutOutbox);
        assertAll(
            () -> assertThat(first).extracting(OrderBackfillInfo::result)
                .containsExactly(OrderBackfillResult.RECORDED, OrderBackfillResult.ALREADY_RECORDED, null),
            () -> assertThat(first.get(2).error()).contains("IllegalStateException"),
            () -> assertThat(second).extracting(OrderBackfillInfo::result)
                .containsExactly(OrderBackfillResult.ALREADY_RECORDED, OrderBackfillResult.ALREADY_RECORDED),
            () -> assertThat(outboxCount()).isEqualTo(2),
            () -> assertThat(backfilled.getEventId()).isEqualTo("order-confirmed:v1:" + withoutOutbox.getId()),
            () -> assertThat(backfilled.getStatus()).isEqualTo(OrderOutboxStatus.PENDING),
            () -> assertThat(backfilled.getPayload()).contains("\"orderId\":" + withoutOutbox.getId()),
            () -> assertThat(outboxOf(recorded).getPayload()).isEqualTo(recordedPayload),
            () -> assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.CONFIRMED),
            () -> assertThat(reloaded.getConfirmedAt()).isEqualTo(confirmedAt),
            () -> assertThat(reload(draft).getStatus()).isEqualTo(OrderStatus.DRAFT),
            () -> assertThat(stockOf(product)).isEqualTo(stock),
            () -> assertThat(paymentCount()).isEqualTo(payments)
        );
    }

    /** 6주차까지처럼 Outbox 없이 확정된 주문. 확정 메서드를 거치지 않아 Outbox 를 남기지 않음 */
    private void confirmWithPointLikeBeforeOutbox(Order order) {
        transactionTemplate.executeWithoutResult(status ->
            entityManager.find(Order.class, order.getId()).confirmWithPoint(1_000L, ZonedDateTime.now()));
    }

    private Product persistProduct(long price) {
        return transactionTemplate.execute(status -> {
            Product product = new Product(brand, "상품", price);
            product.changeStock(10);
            entityManager.persist(product);
            return product;
        });
    }

    private Order persistDraft(Product product) {
        return transactionTemplate.execute(status -> {
            Order order = Order.draft(USER_ID, List.of(new OrderLine(product.getId(), product.getName(), product.getPrice(), 1)), ZonedDateTime.now());
            entityManager.persist(order);
            return order;
        });
    }

    private void confirm(Order order) {
        orderPaymentFacade.confirm(USER_ID, order.getId(), new ConfirmCommand("attempt-" + order.getId(), "TEST", "0000-0000-0000-0000"));
    }

    private OrderConfirmedOutbox outboxOf(Order order) {
        return transactionTemplate.execute(status -> entityManager
            .createQuery("select o from OrderConfirmedOutbox o where o.orderId = :orderId", OrderConfirmedOutbox.class)
            .setParameter("orderId", order.getId())
            .getSingleResult());
    }

    private Order reload(Order order) {
        return transactionTemplate.execute(status -> entityManager.find(Order.class, order.getId()));
    }

    private int stockOf(Product product) {
        return transactionTemplate.execute(status -> entityManager.find(Product.class, product.getId()).getStock());
    }

    private long outboxCount() {
        return countOf("order_confirmed_outbox");
    }

    private long paymentCount() {
        return countOf("payment");
    }

    private long countOf(String table) {
        return transactionTemplate.execute(status ->
            ((Number) entityManager.createNativeQuery("select count(*) from " + table).getSingleResult()).longValue());
    }
}
