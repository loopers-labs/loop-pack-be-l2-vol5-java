package com.loopers.application.order.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.facade.OrderPaymentFacade;
import com.loopers.application.order.facade.OrderPaymentFacade.ConfirmCommand;
import com.loopers.domain.brand.entity.Brand;
import com.loopers.domain.order.entity.Order;
import com.loopers.domain.order.service.OrderService;
import com.loopers.domain.order.vo.OrderLine;
import com.loopers.domain.payment.entity.Payment;
import com.loopers.domain.product.entity.Product;
import com.loopers.support.kafka.OrderEventsTopicReader;
import com.loopers.support.pg.TestPgServer;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 6주차 설계 10.2 의 발행 장면. 실제 broker 의 topic 에서 이번 행동 전후 사이에 생긴 record 만 읽음.
 * 같은 topic 을 다른 테스트도 쓰므로 offset 으로 구간을 나누고, flush 로 보낸 것이 모두 반영된 뒤 끝 offset 까지 읽어 대기 없이 판정함
 */
@SpringBootTest
class OrderConfirmedPublicationTest {

    private static final Long USER_ID = 1L;

    @Autowired
    private OrderPaymentFacade orderPaymentFacade;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderEventsTopicReader topicReader;

    @Autowired
    private ProducerFactory<Object, Object> producerFactory;

    @Autowired
    private ObjectMapper objectMapper;

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

    private Map<TopicPartition, Long> endOffsets() {
        return topicReader.endOffsets();
    }

    private List<ConsumerRecord<String, String>> recordsSince(Map<TopicPartition, Long> from) {
        return topicReader.recordsSince(from);
    }

    private JsonNode payloadOf(ConsumerRecord<String, String> record) throws Exception {
        return objectMapper.readTree(record.value());
    }

    @DisplayName("주문이 처음 확정되면, ")
    @Nested
    class FirstConfirmation {
        @DisplayName("PG SUCCESS 반영 commit 뒤 주문 key 로 저장된 확정 시각 · 품목과 같은 record 하나를 보낸다.")
        @Test
        void publishesOnce_whenPgSucceeds() throws Exception {
            // arrange
            Order order = persistDraft(persistProduct(1_000L), persistProduct(2_000L));
            confirm(order);
            pg.complete(onlyTransactionKeyOf(order), "SUCCESS");
            Map<TopicPartition, Long> before = endOffsets();

            // act
            orderPaymentFacade.reconcile(USER_ID, order.getId());

            // assert
            List<ConsumerRecord<String, String>> records = recordsSince(before);
            Order saved = reload(order);
            assertThat(records).hasSize(1);
            JsonNode payload = payloadOf(records.get(0));
            assertAll(
                () -> assertThat(records.get(0).key()).isEqualTo(String.valueOf(order.getId())),
                () -> assertThat(payload.get("eventType").asText()).isEqualTo("OrderConfirmed"),
                () -> assertThat(payload.get("schemaVersion").asInt()).isEqualTo(1),
                () -> assertThat(payload.get("eventId").asText()).isEqualTo("order-confirmed:v1:" + order.getId()),
                () -> assertThat(payload.get("orderId").asLong()).isEqualTo(order.getId()),
                () -> assertThat(Instant.parse(payload.get("confirmedAt").asText())).isEqualTo(saved.getConfirmedAt().toInstant()),
                () -> assertThat(payload.get("items")).hasSize(2),
                () -> assertThat(payload.get("items").get(0).get("productId").asLong()).isEqualTo(saved.getItems().get(0).getProductId()),
                () -> assertThat(payload.get("items").get(0).get("quantity").asInt()).isEqualTo(1),
                () -> assertThat(payload.get("items").get(1).get("quantity").asInt()).isEqualTo(2),
                () -> assertThat(payload.has("cardNo")).isFalse()
            );
        }

        @DisplayName("callback 이 reconcile 보다 먼저 와 확정해도 record 하나를 보내고, 뒤늦은 reconcile 은 다시 보내지 않는다.")
        @Test
        void publishesOnce_whenCallbackConfirmsFirst() {
            // arrange
            Order order = persistDraft(persistProduct(1_000L));
            confirm(order);
            pg.complete(onlyTransactionKeyOf(order), "SUCCESS");
            Map<TopicPartition, Long> before = endOffsets();

            // act
            orderPaymentFacade.handleCallback(Payment.providerOrderIdOf(order.getId()));
            orderPaymentFacade.reconcile(USER_ID, order.getId());

            // assert
            List<ConsumerRecord<String, String>> records = recordsSince(before);
            assertAll(
                () -> assertThat(records).hasSize(1),
                () -> assertThat(records.get(0).key()).isEqualTo(String.valueOf(order.getId()))
            );
        }

        @DisplayName("0원 주문을 PG 없이 확정해도 record 하나를 보낸다. (D-70)")
        @Test
        void publishesOnce_whenZeroAmount() throws Exception {
            // arrange
            Order order = persistDraft(persistProduct(0L));
            Map<TopicPartition, Long> before = endOffsets();

            // act
            confirm(order);

            // assert
            List<ConsumerRecord<String, String>> records = recordsSince(before);
            assertAll(
                () -> assertThat(records).hasSize(1),
                () -> assertThat(payloadOf(records.get(0)).get("orderId").asLong()).isEqualTo(order.getId())
            );
        }
    }

    @DisplayName("처음 확정이 아니면, record 를 보내지 않는다.")
    @Nested
    class NoPublication {
        @DisplayName("결제 대기 · 결과 미확인 · 결제 실패에서는 보내지 않는다.")
        @Test
        void doesNotPublish_whenNotConfirmed() {
            // arrange
            Product product = persistProduct(1_000L);
            Order pending = persistDraft(product);
            Order unknown = persistDraft(product);
            Order failed = persistDraft(product);
            Map<TopicPartition, Long> before = endOffsets();

            // act
            confirm(pending);
            pg.willDropResponseAfterCreate();
            confirm(unknown);
            pg.willAccept();
            confirm(failed);
            pg.complete(onlyTransactionKeyOf(failed), "FAILED");
            orderPaymentFacade.reconcile(USER_ID, failed.getId());

            // assert
            assertThat(recordsSince(before)).isEmpty();
        }

        @DisplayName("이미 확정된 주문에 reconcile · callback 이 다시 와도 보내지 않는다.")
        @Test
        void doesNotPublishAgain_whenAppliedAgain() {
            // arrange
            Order order = persistDraft(persistProduct(1_000L));
            confirm(order);
            pg.complete(onlyTransactionKeyOf(order), "SUCCESS");
            orderPaymentFacade.reconcile(USER_ID, order.getId());
            Map<TopicPartition, Long> before = endOffsets();

            // act
            orderPaymentFacade.reconcile(USER_ID, order.getId());
            orderPaymentFacade.handleCallback(Payment.providerOrderIdOf(order.getId()));

            // assert
            assertThat(recordsSince(before)).isEmpty();
        }

        @DisplayName("이전 POINT 확정 주문은 소급 발행하지 않는다. (D-70)")
        @Test
        void doesNotPublish_whenConfirmedWithPoint() {
            // arrange
            Order order = persistDraft(persistProduct(1_000L));
            Map<TopicPartition, Long> before = endOffsets();

            // act
            transactionTemplate.executeWithoutResult(status ->
                entityManager.find(Order.class, order.getId()).confirmWithPoint(1_000L, ZonedDateTime.now()));

            // assert
            assertThat(recordsSince(before)).isEmpty();
        }

        @DisplayName("확정한 트랜잭션이 rollback 되면 보내지 않는다.")
        @Test
        void doesNotPublish_whenRolledBack() {
            // arrange
            Order order = persistDraft(persistProduct(1_000L));
            confirm(order);
            Map<TopicPartition, Long> before = endOffsets();

            // act
            transactionTemplate.executeWithoutResult(status -> {
                orderService.confirmPaid(orderService.getOrderForUpdate(order.getId()), 1_000L);
                status.setRollbackOnly();
            });

            // assert
            assertThat(recordsSince(before)).isEmpty();
        }
    }

    @DisplayName("producer 는 commerce-api 의 max.block.ms 를 실제 설정으로 받는다. (D-75, D-93)")
    @Test
    void producerReceivesMaxBlock() {
        assertThat(String.valueOf(producerFactory.getConfigurationProperties().get(ProducerConfig.MAX_BLOCK_MS_CONFIG))).isEqualTo("500");
    }
}
