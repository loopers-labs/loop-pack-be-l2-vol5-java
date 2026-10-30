package com.loopers.application.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.dto.OrderItemCommand;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import com.loopers.infrastructure.order.OrderEventKafkaPublisher;
import com.loopers.support.kafka.TopicReader;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;

/**
 * W6 의 직접 발행에 남은 <b>누락 구간</b>(주문 확정 commit 과 Kafka 전송 사이)을 두 사례로 재현한다. 다음 주 Outbox 로
 * 개선할 출발점이며, 이 테스트는 W6 의 직접 발행 경로를 그대로 실행한다. Outbox 를 실행한 뒤 행을 지워 누락을 꾸미지 않는다.
 *
 * <ul>
 *   <li>사례 A (미발행): 최초 확정 commit 뒤, 전송 호출 직전의 hook 에서 전송을 건너뛴다. 프로세스를 강제 종료한 것이 아니라
 *       호출을 건너뛴 것이다. 주문 CONFIRMED·재고 차감은 남고 broker 에는 이 eventId 의 record 가 없다.</li>
 *   <li>사례 B (정상): 같은 경로에서 전송이 진행돼 broker 에 record 한 건이 남는다. 이 record 를 소비해 구매 활동을 만드는
 *       쪽은 commerce-streamer 의 {@code OrderConfirmedConsumerTest} 가 같은 계약으로 증명한다(두 앱은 서로 의존하지 않는다).</li>
 * </ul>
 *
 * <p>실행: {@code ./gradlew :apps:commerce-api:test --tests '*OrderConfirmedPublicationGapTest'}. 시계는 2026-09-07T01:00:00Z 로
 * 고정한다. 실제 주문·결제 시도 ID 는 실행마다 새로 생기며 {@code [W6-OBSERVATION]} 로그로 남긴다.
 */
class OrderConfirmedPublicationGapTest extends PaymentFlowTestSupport {

    private static final Logger log = LoggerFactory.getLogger(OrderConfirmedPublicationGapTest.class);
    private static final String TOPIC = "order-events-gap-" + UUID.randomUUID();
    private static final Duration AWAIT = Duration.ofSeconds(10);

    @DynamicPropertySource
    static void topicProperty(DynamicPropertyRegistry registry) {
        registry.add("order-event.topic", () -> TOPIC);
    }

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-09-07T01:00:00Z"), ZoneOffset.UTC);
        }
    }

    @MockitoSpyBean
    private OrderEventKafkaPublisher publisher;

    @Autowired
    private OrderService orderService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KafkaTemplate<Object, Object> kafkaTemplate;

    private TopicReader reader;

    @BeforeEach
    void openReader() {
        reader = new TopicReader(KafkaTestContainersConfig.bootstrapServers(), TOPIC);
    }

    @AfterEach
    void closeReader() {
        reader.close();
    }

    // 결제를 시작해 PG 가 거래를 수락한 상태(PENDING)까지 진행한다.
    private Long startPayment(User user, String attemptKey, OrderItemCommand... items) {
        Long orderId = orderFacade.create(user.getId(), List.of(items)).id();
        orderFacade.confirm(command(user, orderId, attemptKey));
        return orderId;
    }

    // PG 성공을 알리는 callback 으로 실제 결과 적용 경로를 지난다.
    private void confirmByCallback(Long orderId) {
        String transactionKey = pgKeyOf(orderId);
        pg.setStatus(transactionKey, "SUCCESS");
        paymentResultLookupFacade.lookupPaymentResultOnPgCallback(providerOrderIdOf(orderId), transactionKey);
    }

    private void logCase(String role, Long orderId, String items, long brokerRecords) {
        log.info("[W6-OBSERVATION] role={} orderId={} providerOrderId={} transactionKey={} eventId=order-confirmed:v1:{} "
                + "confirmedAt={} items={} orderStatus={} brokerRecordsForEventId={}",
            role, orderId, providerOrderIdOf(orderId), pgKeyOf(orderId), orderId,
            orderService.get(orderId).getConfirmedAt().toInstant(), items, orderStatusOf(orderId), brokerRecords);
    }

    @DisplayName("확정 commit 뒤 전송 호출 직전에 멈춘 주문은 CONFIRMED 와 재고 차감이 남지만 broker 에 이벤트가 없고, 같은 경로로 전송이 진행된 주문은 이벤트가 한 건 남는다.")
    @Test
    void leavesConfirmedOrderWithoutEvent_whenSendIsInterruptedAfterCommit() throws Exception {
        // arrange: 러닝화(3,500원, 재고 10) · 양말(2,000원, 재고 10). A 는 러닝화 2 + 양말 1, B 는 러닝화 1 이다.
        User user = saveUser(0);
        Product shoes = saveProduct("러닝화", 3_500, 10);
        Product socks = saveProduct("양말", 2_000, 10);
        Long orderA = startPayment(user, "attempt-A", new OrderItemCommand(shoes.getId(), 2), new OrderItemCommand(socks.getId(), 1));
        Long orderB = startPayment(user, "attempt-B", new OrderItemCommand(shoes.getId(), 1));
        AtomicReference<String> seenAtHook = new AtomicReference<>();
        doAnswer(call -> {
            // commit 은 이미 끝났고 전송은 아직인 시점의 DB 를 새로 읽어 기록한 뒤, 전송을 건너뛴다.
            seenAtHook.set(orderStatusOf(orderA) + "/shoes=" + stockOf(shoes) + "/socks=" + stockOf(socks));
            return null;
        }).when(publisher).publish(argThat(event -> event.orderId().equals(orderA)));

        // act: 두 주문을 같은 결과 적용 경로로 확정한다
        confirmByCallback(orderA);
        confirmByCallback(orderB);

        // assert A: commit 직후 이미 CONFIRMED·재고 차감이 보였고, 전송이 건너뛰어져도 주문은 되돌려지지 않았다
        assertThat(seenAtHook.get()).isEqualTo("CONFIRMED/shoes=7/socks=9");
        assertThat(orderStatusOf(orderA)).isEqualTo("CONFIRMED");
        assertThat(stockOf(socks)).isEqualTo(9);
        assertThat(pg.createCalls()).isEqualTo(2);

        // assert B: 이벤트는 B 의 것 한 건뿐이다. 필드는 고정 시계와 저장된 품목을 따른다
        List<ConsumerRecord<String, byte[]>> records = reader.awaitRecords(1, AWAIT);
        assertThat(records).hasSize(1);
        JsonNode body = objectMapper.readTree(records.get(0).value());
        assertThat(records.get(0).key()).isEqualTo(String.valueOf(orderB));
        assertThat(body.get("eventId").asText()).isEqualTo("order-confirmed:v1:" + orderB);
        assertThat(body.get("confirmedAt").asText()).isEqualTo("2026-09-07T01:00:00Z");
        assertThat(body.get("items")).hasSize(1);
        kafkaTemplate.flush();
        assertThat(reader.recordsSinceOpen()).isEqualTo(1);

        logCase("A 미발행", orderA, "[러닝화x2, 양말x1]", 0);
        logCase("B 정상", orderB, "[러닝화x1]", 1);
        log.info("[W6-OBSERVATION] hook 시점(commit 후, send 전)의 DB: {} (재고는 두 주문의 차감 합: 러닝화 2+1, 양말 1)", seenAtHook.get());
    }
}
