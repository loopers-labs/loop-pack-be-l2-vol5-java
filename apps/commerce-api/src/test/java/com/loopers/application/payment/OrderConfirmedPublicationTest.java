package com.loopers.application.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.dto.OrderItemCommand;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import com.loopers.support.kafka.TopicReader;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 PG 확정 경로가 실제 broker 에 OrderConfirmed 를 남기는지(그리고 남기지 않아야 할 때 남기지 않는지) 확인한다. */
class OrderConfirmedPublicationTest extends PaymentFlowTestSupport {

    private static final String TOPIC = "order-events-publication-" + UUID.randomUUID();
    private static final Duration AWAIT = Duration.ofSeconds(10);

    @DynamicPropertySource
    static void topicProperty(DynamicPropertyRegistry registry) {
        registry.add("order-event.topic", () -> TOPIC);
    }

    @Autowired
    private OrderService orderService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KafkaTemplate<Object, Object> kafkaTemplate;

    private TopicReader reader;
    private User user;
    private Product shoes;
    private Product socks;
    private Long orderId;
    private String transactionKey;

    @BeforeEach
    void openReader() {
        reader = new TopicReader(KafkaTestContainersConfig.bootstrapServers(), TOPIC);
    }

    @AfterEach
    void closeReader() {
        reader.close();
    }

    // 러닝화 3,500 × 2 + 양말 2,000 × 1 = 9,000원 주문을 결제 시작(PENDING)까지 진행한다.
    private void startPending() {
        user = saveUser(0);
        shoes = saveProduct("러닝화", 3_500, 5);
        socks = saveProduct("양말", 2_000, 5);
        orderId = orderFacade.create(user.getId(), List.of(
            new OrderItemCommand(shoes.getId(), 2),
            new OrderItemCommand(socks.getId(), 1)
        )).id();
        orderFacade.confirm(command(user, orderId, "attempt-1"));
        transactionKey = pgKeyOf(orderId);
    }

    private void callback() {
        paymentResultLookupFacade.lookupPaymentResultOnPgCallback(providerOrderIdOf(orderId), transactionKey);
    }

    private void reconcile() {
        paymentResultLookupFacade.lookupMyPaymentResult(user.getId(), orderId);
    }

    // 비동기 전송이 모두 끝난 뒤에 쓰인 record 수. 기다리는 시간으로 "없음"을 증명하지 않는다.
    private long recordCountAfterFlush() {
        kafkaTemplate.flush();
        return reader.recordsSinceOpen();
    }

    @DisplayName("알림으로 PG 성공을 처음 반영하면, 저장된 주문의 품목·수량·확정 시각을 담은 OrderConfirmed 가 orderId key 로 한 건 남는다.")
    @Test
    void publishesOrderConfirmed_onFirstSuccess() throws Exception {
        // arrange
        startPending();
        pg.setStatus(transactionKey, "SUCCESS");

        // act
        callback();

        // assert
        List<ConsumerRecord<String, byte[]>> records = reader.awaitRecords(1, AWAIT);
        assertThat(records).hasSize(1);
        ConsumerRecord<String, byte[]> record = records.get(0);
        JsonNode body = objectMapper.readTree(record.value());
        assertThat(record.key()).isEqualTo(String.valueOf(orderId));
        assertThat(body.get("eventType").asText()).isEqualTo("OrderConfirmed");
        assertThat(body.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(body.get("eventId").asText()).isEqualTo("order-confirmed:v1:" + orderId);
        assertThat(body.get("orderId").asLong()).isEqualTo(orderId);
        assertThat(body.get("confirmedAt").asText()).endsWith("Z");
        assertThat(Instant.parse(body.get("confirmedAt").asText()))
            .isEqualTo(orderService.get(orderId).getConfirmedAt().toInstant());
        Set<String> items = new HashSet<>();
        body.get("items").forEach(item -> items.add(item.get("productId").asLong() + "x" + item.get("quantity").asInt()));
        assertThat(items).containsExactlyInAnyOrder(shoes.getId() + "x2", socks.getId() + "x1");
    }

    @DisplayName("이미 확정된 주문에 같은 성공이 알림·확인으로 다시 와도 이벤트와 재고 차감은 늘지 않는다.")
    @Test
    void doesNotPublishAgain_onRepeatedSuccess() {
        // arrange
        startPending();
        pg.setStatus(transactionKey, "SUCCESS");
        callback();
        assertThat(reader.awaitRecords(1, AWAIT)).hasSize(1);

        // act
        callback();
        reconcile();

        // assert
        assertThat(recordCountAfterFlush()).isEqualTo(1);
        assertThat(stockOf(shoes)).isEqualTo(3);
        assertThat(stockOf(socks)).isEqualTo(4);
    }

    @DisplayName("PG 가 처리 중이거나 실패로 알려 주면 확정되지 않으므로 이벤트가 나가지 않는다.")
    @Test
    void doesNotPublish_whenNotConfirmed() {
        // arrange
        startPending();

        // act: 처리 중 → 실패
        reconcile();
        long afterPending = recordCountAfterFlush();
        pg.setStatus(transactionKey, "FAILED");
        reconcile();

        // assert
        assertThat(afterPending).isZero();
        assertThat(orderStatusOf(orderId)).isEqualTo("PAYMENT_FAILED");
        assertThat(recordCountAfterFlush()).isZero();
    }
}
