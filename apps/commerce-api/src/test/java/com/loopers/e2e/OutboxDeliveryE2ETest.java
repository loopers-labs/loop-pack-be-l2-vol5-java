package com.loopers.e2e;

import com.loopers.application.outbox.OutboxPublisher;
import com.loopers.application.outbox.OutboxRelay;
import com.loopers.application.payment.PaymentFlowTestSupport;
import com.loopers.contract.order.OrderConfirmed;
import com.loopers.domain.outbox.OutboxService;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.order.OrderDto;
import com.loopers.support.kafka.TopicReader;
import com.loopers.support.streamer.StreamerProcess;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 주문 API → 확정 TX 의 Outbox → relay(broker 확인 뒤 중단 → 재시작) → 실제 streamer → 구매 활동을 한 실행에서 잇는다.
 * streamer 는 별도 프로세스이고, 중단은 예외로 만든 것이며 프로세스 강제 종료가 아니다.
 */
@Tag("e2e")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OutboxDeliveryE2ETest extends PaymentFlowTestSupport {

    private static final String TOPIC = "outbox-delivery-e2e-" + UUID.randomUUID();

    @DynamicPropertySource
    static void topicProperty(DynamicPropertyRegistry registry) {
        registry.add("order-event.topic", () -> TOPIC);
    }

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Clock clock;

    @DisplayName("주문을 확정하고 relay 가 broker 확인 뒤 멈췄다가 재시작해 같은 사건을 두 번 보내도, 구매 활동은 상품별로 한 번씩만 남는다.")
    @Test
    void savesPurchaseActivityOnce_whenRelayRepublishesAfterStop() throws Exception {
        // given: 포인트 10,000원 사용자, 러닝화 3,500원(재고 5)·양말 2,000원(재고 5), 이 테스트만 쓰는 topic·group 의 streamer
        User user = saveUser(10_000);
        Product shoes = saveProduct("러닝화", 3_500, 5);
        Product socks = saveProduct("양말", 2_000, 5);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", String.valueOf(user.getId()));
        String consumerGroup = "purchase-activity-" + UUID.randomUUID();
        TopicReader.createTopic(KafkaTestContainersConfig.bootstrapServers(), TOPIC);

        try (StreamerProcess streamer = StreamerProcess.start(TOPIC, consumerGroup);
             TopicReader reader = new TopicReader(KafkaTestContainersConfig.bootstrapServers(), TOPIC)) {

            // 1. 주문 API 로 주문하고 결제를 시작한 뒤, PG 성공을 결과 확인 API 로 반영한다.
            ResponseEntity<ApiResponse<OrderDto.OrderResponse>> created = testRestTemplate.exchange(
                "/api/v1/orders", HttpMethod.POST,
                new HttpEntity<>("{\"items\":[{\"productId\":" + shoes.getId() + ",\"quantity\":2},"
                    + "{\"productId\":" + socks.getId() + ",\"quantity\":1}]}", headers),
                new ParameterizedTypeReference<>() {}
            );
            Long orderId = created.getBody().data().id();
            testRestTemplate.exchange(
                "/api/v1/orders/" + orderId + "/confirm", HttpMethod.POST,
                new HttpEntity<>("{\"paymentAttemptKey\":\"attempt-1\",\"cardType\":\"TEST\",\"cardNo\":\"0000-0000-0000-0000\"}", headers),
                new ParameterizedTypeReference<ApiResponse<OrderDto.PaymentStartResponse>>() {}
            );
            pg.setStatus(pgKeyOf(orderId), "SUCCESS");
            ResponseEntity<ApiResponse<OrderDto.PaymentStartResponse>> reconciled = testRestTemplate.exchange(
                "/api/v1/orders/" + orderId + "/payment/reconcile", HttpMethod.POST, new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {}
            );
            String eventId = "order-confirmed:v1:" + orderId;

            assertThat(reconciled.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(reconciled.getBody().data().orderStatus()).isEqualTo("CONFIRMED");
            assertThat(stockOf(shoes)).isEqualTo(3);
            assertThat(stockOf(socks)).isEqualTo(4);
            assertThat(jdbcTemplate.queryForObject("select status from outbox where event_id = ?", String.class, eventId))
                .isEqualTo("PENDING");

            // 2. 첫 relay 가 broker 확인을 받은 직후, PUBLISHED 를 저장하기 전에 멈춘다.
            OutboxPublisher stopsAfterBrokerAck = outbox -> {
                outboxPublisher.publish(outbox);
                throw new IllegalStateException("broker 확인 뒤 중단");
            };
            OutboxRelay firstRelay = new OutboxRelay(
                outboxService, stopsAfterBrokerAck, transactionManager, clock, 20, Duration.ofSeconds(30)
            );
            assertThatThrownBy(firstRelay::relayOnce).hasMessage("broker 확인 뒤 중단");

            assertThat(reader.recordsSinceOpen()).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("select status from outbox where event_id = ?", String.class, eventId))
                .isEqualTo("PENDING");

            // 3. relay 를 새로 만들어 같은 Outbox 를 다시 보낸다.
            OutboxRelay restartedRelay = new OutboxRelay(
                outboxService, outboxPublisher, transactionManager, clock, 20, Duration.ofSeconds(30)
            );
            restartedRelay.relayOnce();

            List<ConsumerRecord<String, byte[]>> records = reader.awaitRecords(2, Duration.ofSeconds(10));
            assertThat(records).hasSize(2);
            assertThat(OrderConfirmed.getDecoder().decode(records.get(0).value()).getEventId()).isEqualTo(eventId);
            assertThat(OrderConfirmed.getDecoder().decode(records.get(1).value()).getEventId()).isEqualTo(eventId);
            assertThat(records.get(1).offset()).isNotEqualTo(records.get(0).offset());
            assertThat(jdbcTemplate.queryForObject("select status from outbox where event_id = ?", String.class, eventId))
                .isEqualTo("PUBLISHED");

            // 4. streamer 가 두 record 를 모두 처리하고 ack 할 때까지(commit offset 2) 기다린다.
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            long committedOffset = 0;
            while (committedOffset < 2 && System.nanoTime() < deadline) {
                Thread.sleep(200);
                committedOffset = TopicReader.committedOffset(KafkaTestContainersConfig.bootstrapServers(), TOPIC, consumerGroup);
            }
            assertThat(committedOffset).as(streamer.logTail()).isEqualTo(2);

            // 5. 같은 사건을 두 번 받았지만 구매 활동은 상품별로 한 번씩, 완료 기록은 하나다. 결제·재고·포인트는 그대로다.
            List<String> activities = jdbcTemplate.query(
                "select product_id, quantity from product_purchase_activity where order_id = ? order by product_id",
                (rs, rowNum) -> rs.getLong("product_id") + "x" + rs.getInt("quantity"),
                orderId
            );
            assertThat(activities).containsExactly(shoes.getId() + "x2", socks.getId() + "x1");
            assertThat(jdbcTemplate.queryForObject(
                "select count(*) from processed_order_confirmation where order_id = ? and event_id = ?", Long.class, orderId, eventId
            )).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject("select count(*) from outbox where event_id = ?", Long.class, eventId))
                .isEqualTo(1L);
            assertThat(pg.createCalls()).isEqualTo(1);
            assertThat(pg.transactionCount()).isEqualTo(1);
            assertThat(stockOf(shoes)).isEqualTo(3);
            assertThat(stockOf(socks)).isEqualTo(4);
            assertThat(balanceOf(user)).isEqualTo(10_000L);
        }
    }
}
