package com.loopers.application.order.facade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.facade.OrderPaymentFacade.ConfirmCommand;
import com.loopers.domain.brand.entity.Brand;
import com.loopers.domain.order.entity.Order;
import com.loopers.domain.order.entity.OrderConfirmedOutbox;
import com.loopers.domain.order.enums.OrderOutboxStatus;
import com.loopers.domain.order.vo.OrderConfirmed;
import com.loopers.domain.order.vo.OrderLine;
import com.loopers.domain.payment.entity.Payment;
import com.loopers.domain.product.entity.Product;
import com.loopers.support.pg.TestPgServer;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 6주차 발행 조건(D-70)이 Outbox 기록 조건으로 그대로 옮겨졌는지 실제 MySQL 의 행으로 확인함 (7주차 설계 3.4) */
@SpringBootTest
class OrderOutboxRecordTest {

    private static final Long USER_ID = 1L;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private OrderPaymentFacade orderPaymentFacade;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private final TestPgServer pg = TestPgServer.shared();
    private Brand brand;

    @BeforeEach
    void setUp() {
        pg.reset();
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

    @DisplayName("주문이 처음 확정되면, ")
    @Nested
    class FirstConfirmation {
        @DisplayName("PG SUCCESS 반영과 함께 주문 key 로, 저장된 확정 시각 · 품목과 같은 payload 의 PENDING 한 행을 남긴다.")
        @Test
        void recordsOnce_whenPgSucceeds() throws Exception {
            // arrange
            Order order = persistDraft(persistProduct(1_000L), persistProduct(2_000L));
            confirm(order);
            pg.complete(onlyTransactionKeyOf(order), "SUCCESS");

            // act
            orderPaymentFacade.reconcile(USER_ID, order.getId());

            // assert
            List<OrderConfirmedOutbox> outboxes = outboxesOf(order);
            Order saved = reload(order);
            assertThat(outboxes).hasSize(1);
            OrderConfirmedOutbox outbox = outboxes.get(0);
            JsonNode payload = OBJECT_MAPPER.readTree(outbox.getPayload());
            assertAll(
                () -> assertThat(outbox.getEventId()).isEqualTo("order-confirmed:v1:" + order.getId()),
                () -> assertThat(outbox.getMessageKey()).isEqualTo(String.valueOf(order.getId())),
                () -> assertThat(outbox.getStatus()).isEqualTo(OrderOutboxStatus.PENDING),
                () -> assertThat(outbox.getAttemptCount()).isZero(),
                () -> assertThat(payload.get("eventType").asText()).isEqualTo("OrderConfirmed"),
                () -> assertThat(payload.get("schemaVersion").asInt()).isEqualTo(1),
                () -> assertThat(payload.get("eventId").asText()).isEqualTo(outbox.getEventId()),
                () -> assertThat(payload.get("orderId").asLong()).isEqualTo(order.getId()),
                () -> assertThat(Instant.parse(payload.get("confirmedAt").asText())).isEqualTo(saved.getConfirmedAt().toInstant()),
                () -> assertThat(payload.get("items")).hasSize(2),
                () -> assertThat(payload.get("items").get(0).get("productId").asLong()).isEqualTo(saved.getItems().get(0).getProductId()),
                () -> assertThat(payload.get("items").get(0).get("quantity").asInt()).isEqualTo(1),
                () -> assertThat(payload.get("items").get(1).get("quantity").asInt()).isEqualTo(2),
                () -> assertThat(payload.has("cardNo")).isFalse()
            );
        }

        @DisplayName("callback 이 reconcile 보다 먼저 와 확정해도 한 행만 남기고, 뒤늦은 reconcile 은 더하지 않는다.")
        @Test
        void recordsOnce_whenCallbackConfirmsFirst() {
            // arrange
            Order order = persistDraft(persistProduct(1_000L));
            confirm(order);
            pg.complete(onlyTransactionKeyOf(order), "SUCCESS");

            // act
            orderPaymentFacade.handleCallback(Payment.providerOrderIdOf(order.getId()));
            orderPaymentFacade.reconcile(USER_ID, order.getId());

            // assert
            assertThat(outboxesOf(order)).hasSize(1);
        }

        @DisplayName("0원 주문을 PG 없이 확정해도 한 행을 남긴다. (6주차 D-70)")
        @Test
        void recordsOnce_whenZeroAmount() {
            // arrange
            Order order = persistDraft(persistProduct(0L));

            // act
            confirm(order);

            // assert
            assertThat(outboxesOf(order)).singleElement()
                .extracting(OrderConfirmedOutbox::getOrderId).isEqualTo(order.getId());
        }
    }

    @DisplayName("처음 확정이 아니면, Outbox 를 남기지 않는다.")
    @Nested
    class NoRecord {
        @DisplayName("결제 대기 · 결과 미확인 · 결제 실패에서는 남기지 않는다.")
        @Test
        void doesNotRecord_whenNotConfirmed() {
            // arrange
            Product product = persistProduct(1_000L);
            Order pending = persistDraft(product);
            Order unknown = persistDraft(product);
            Order failed = persistDraft(product);

            // act
            confirm(pending);
            pg.willDropResponseAfterCreate();
            confirm(unknown);
            pg.willAccept();
            confirm(failed);
            pg.complete(onlyTransactionKeyOf(failed), "FAILED");
            orderPaymentFacade.reconcile(USER_ID, failed.getId());

            // assert
            assertThat(outboxCount()).isZero();
        }

        @DisplayName("이미 확정된 주문에 reconcile · callback 이 다시 와도 행이 늘지 않고 처음 내용 그대로다.")
        @Test
        void doesNotRecordAgain_whenAppliedAgain() {
            // arrange
            Order order = persistDraft(persistProduct(1_000L));
            confirm(order);
            pg.complete(onlyTransactionKeyOf(order), "SUCCESS");
            orderPaymentFacade.reconcile(USER_ID, order.getId());
            String firstPayload = outboxesOf(order).get(0).getPayload();

            // act
            orderPaymentFacade.reconcile(USER_ID, order.getId());
            orderPaymentFacade.handleCallback(Payment.providerOrderIdOf(order.getId()));

            // assert
            assertThat(outboxesOf(order)).singleElement()
                .extracting(OrderConfirmedOutbox::getPayload).isEqualTo(firstPayload);
        }

        @DisplayName("이전 POINT 확정 주문은 소급해 남기지 않는다. (6주차 D-70)")
        @Test
        void doesNotRecord_whenConfirmedWithPoint() {
            // arrange
            Order order = persistDraft(persistProduct(1_000L));

            // act
            transactionTemplate.executeWithoutResult(status ->
                entityManager.find(Order.class, order.getId()).confirmWithPoint(1_000L, ZonedDateTime.now()));

            // assert
            assertThat(outboxCount()).isZero();
        }
    }

    @DisplayName("같은 주문의 두 번째 Outbox 는 eventId 가 달라도 order_id 유일 제약으로 거절된다. (D-100)")
    @Test
    void rejectsSecondOutboxOfSameOrder() {
        // arrange
        Order order = persistDraft(persistProduct(1_000L));
        confirm(order);
        pg.complete(onlyTransactionKeyOf(order), "SUCCESS");
        orderPaymentFacade.reconcile(USER_ID, order.getId());
        OrderConfirmed other = new OrderConfirmed("order-confirmed:v2:" + order.getId(), order.getId(),
            ZonedDateTime.now(), List.of(new OrderConfirmed.Item(1L, 1)));

        // act & assert
        assertThrows(PersistenceException.class, () -> transactionTemplate.executeWithoutResult(status -> {
            entityManager.persist(OrderConfirmedOutbox.pending(other, "{}", ZonedDateTime.now()));
            entityManager.flush();
        }));
        assertThat(outboxesOf(order)).hasSize(1);
    }

    private Product persistProduct(long price) {
        return transactionTemplate.execute(status -> {
            Product product = new Product(brand, "상품", price);
            product.changeStock(10);
            entityManager.persist(product);
            return product;
        });
    }

    private Order persistDraft(Product... products) {
        return transactionTemplate.execute(status -> {
            List<OrderLine> lines = new ArrayList<>();
            for (int i = 0; i < products.length; i++) {
                lines.add(new OrderLine(products[i].getId(), products[i].getName(), products[i].getPrice(), i + 1));
            }
            Order order = Order.draft(USER_ID, lines, ZonedDateTime.now());
            entityManager.persist(order);
            return order;
        });
    }

    private void confirm(Order order) {
        orderPaymentFacade.confirm(USER_ID, order.getId(), new ConfirmCommand("attempt-" + order.getId(), "TEST", "0000-0000-0000-0000"));
    }

    private String onlyTransactionKeyOf(Order order) {
        List<TestPgServer.Transaction> transactions = pg.transactionsOf(Payment.providerOrderIdOf(order.getId()));
        assertThat(transactions).hasSize(1);
        return transactions.get(0).transactionKey();
    }

    private Order reload(Order order) {
        return transactionTemplate.execute(status -> {
            Order found = entityManager.find(Order.class, order.getId());
            found.getItems().size();
            return found;
        });
    }

    private List<OrderConfirmedOutbox> outboxesOf(Order order) {
        return transactionTemplate.execute(status -> entityManager
            .createQuery("select o from OrderConfirmedOutbox o where o.orderId = :orderId", OrderConfirmedOutbox.class)
            .setParameter("orderId", order.getId())
            .getResultList());
    }

    private long outboxCount() {
        return transactionTemplate.execute(status ->
            ((Number) entityManager.createNativeQuery("select count(*) from order_confirmed_outbox").getSingleResult()).longValue());
    }
}
