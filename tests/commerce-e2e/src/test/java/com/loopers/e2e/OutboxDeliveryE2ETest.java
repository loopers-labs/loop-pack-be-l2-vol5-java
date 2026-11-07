package com.loopers.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.OrderConfirmedEvent;
import com.loopers.application.order.OrderEventOutboxProcessor;
import com.loopers.application.order.OrderEventRelay;
import com.loopers.application.order.OrderEventSender;
import com.loopers.application.order.OutboxRelayProperties;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OutboxStatus;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.support.ApiTestClient;
import com.loopers.support.FakePaymentGateway;
import com.loopers.support.fixture.Fixtures;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * §7 실제 주문부터 구매 활동까지 한 실행. 기존 주문 API·로컬 PG fixture(FakePaymentGateway)·실제 MySQL·Kafka·실제 구매 활동 consumer(commerce-streamer).
 * relay 의 첫 broker 확인 뒤 PUBLISHED 저장 전에 멈추고, relay 를 새로 만들어 같은 Outbox 에서 다시 보낸다. 같은 eventId 가 broker 에 두 record 로 남는다.
 * 컨테이너는 이 클래스의 JVM 에만 있다 (forkEvery = 1). 테스트가 띄운 두 context 만 닫는다.
 * 실행: ./gradlew :tests:commerce-e2e:test --tests '*OutboxDeliveryE2ETest' (Docker 필요).
 */
class OutboxDeliveryE2ETest {
    private static final Logger log = LoggerFactory.getLogger(OutboxDeliveryE2ETest.class);
    private static final String TOPIC = "order-events-e2e-delivery";
    private static final String GROUP = "purchase-activity-e2e-delivery";
    private static final long BALANCE = 10_000L;

    private ConfigurableApplicationContext api;
    private ConfigurableApplicationContext streamer;

    /** broker 확인까지는 진짜로 하고 PUBLISHED 저장만 하지 않고 멈춘다. 확인 받은 위치를 남긴다. 나머지는 실제 processor. */
    static class StopBeforePublishedSave extends OrderEventOutboxProcessor {
        private final OrderEventOutboxProcessor delegate;
        private final List<OrderEventSender.Sent> acked = new CopyOnWriteArrayList<>();

        StopBeforePublishedSave(OrderEventOutboxProcessor delegate) {
            super(null, null);
            this.delegate = delegate;
        }

        @Override
        public List<Claimed> claimDue(ZonedDateTime now) {
            return delegate.claimDue(now);
        }

        @Override
        public Optional<Claimed> claim(String eventId, ZonedDateTime now) {
            return delegate.claim(eventId, now);
        }

        @Override
        public void markPublished(String eventId, ZonedDateTime now, OrderEventSender.Sent sent) {
            acked.add(sent);
            throw new IllegalStateException("주입된 중단: broker 확인 뒤, PUBLISHED 저장 전");
        }

        @Override
        public OutboxStatus recordFailure(String eventId, ZonedDateTime now, String error) {
            return delegate.recordFailure(eventId, now, error);
        }

        @Override
        public boolean resume(String eventId, ZonedDateTime now) {
            return delegate.resume(eventId, now);
        }
    }

    @AfterEach
    void tearDown() {
        E2eContexts.closeQuietly(streamer);
        E2eContexts.closeQuietly(api);
    }

    @DisplayName("[§7] 주문 확정 → Outbox 한 행 → broker 확인 뒤 PUBLISHED 저장 전 중단 → relay 재시작으로 재발행 → consumer 가 같은 사건을 두 번 받아도 구매 활동은 상품별 한 행(7×2, 8×1)이고 완료 기록은 하나다.")
    @Test
    void deliversConfirmedOrderToPurchaseActivity_onceAcrossRepublish() {
        E2eContexts.startContainers();
        E2eDb db = new E2eDb();
        api = E2eContexts.startApi(TOPIC, List.of());
        streamer = E2eContexts.startStreamer(TOPIC, GROUP);

        Fixtures fixtures = api.getBean(Fixtures.class);
        fixtures.catalogRevision();
        UserModel user = fixtures.userWithBalance(BALANCE);
        BrandModel brand = fixtures.brand("브랜드");
        List<ProductModel> products = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            products.add(fixtures.product(brand.getId(), "상품 " + i, 1_000L, 10));
        }
        ProductModel product7 = products.get(6);
        ProductModel product8 = products.get(7);
        assertThat(List.of(product7.getId(), product8.getId())).containsExactly(7L, 8L);
        FakePaymentGateway pg = api.getBean(FakePaymentGateway.class);
        ApiTestClient client = new ApiTestClient(
            new TestRestTemplate(new RestTemplateBuilder().rootUri("http://localhost:" + E2eContexts.port(api))), api.getBean(ObjectMapper.class));

        // 1. 기존 API 로 주문·확정 (PG SUCCESS → 내부 확정 경로)
        long orderId = client.post("/api/v1/orders", user.getId(), Map.of("items", List.of(
                Map.of("productId", product7.getId(), "quantity", 2),
                Map.of("productId", product8.getId(), "quantity", 1))))
            .assertSuccess(HttpStatus.CREATED).data().path("id").asLong();
        String status = client.post("/api/v1/orders/" + orderId + "/confirm", user.getId(),
                Map.of("paymentAttemptKey", "attempt-" + orderId, "cardType", "SAMSUNG", "cardNumber", "1234-5678-9814-1451"))
            .assertSuccess(HttpStatus.OK).data().path("status").asText();
        assertThat(status).isEqualTo("CONFIRMED");
        String eventId = OrderConfirmedEvent.eventIdOf(orderId);

        // 2. 확정 주문·예약 소비·같은 eventId 의 Outbox 한 행
        Map<String, Object> orderRow = db.order(orderId);
        List<Map<String, Object>> ledger = db.ledgerOfOrder(orderId);
        assertThat(orderRow.get("status")).isEqualTo("CONFIRMED");
        assertThat(ledger).extracting(row -> row.get("product_id"), row -> row.get("type"), row -> row.get("quantity"))
            .containsExactly(tuple(7L, "RESERVE", -2), tuple(8L, "RESERVE", -1));
        List<Map<String, Object>> outbox = db.outboxOfOrder(orderId);
        assertThat(outbox).extracting(row -> row.get("event_id"), row -> row.get("status")).containsExactly(tuple(eventId, "PENDING"));
        assertThat(db.activityOfOrder(orderId)).isEmpty();

        // 3. relay 의 첫 broker 확인 뒤 PUBLISHED 저장 전 중단
        OrderEventOutboxProcessor processor = api.getBean(OrderEventOutboxProcessor.class);
        OrderEventSender sender = api.getBean(OrderEventSender.class);
        OutboxRelayProperties properties = api.getBean(OutboxRelayProperties.class);
        Clock clock = api.getBean(Clock.class);
        StopBeforePublishedSave stopping = new StopBeforePublishedSave(processor);
        OrderEventRelay.Run first = new OrderEventRelay(stopping, sender, properties, clock).runDue();

        assertThat(first.aborted()).isTrue();
        assertThat(stopping.acked).hasSize(1);
        assertThat(db.outboxOfOrder(orderId)).extracting(row -> row.get("status")).containsExactly("PENDING");

        // 4. 같은 Outbox 에서 relay 재시작 (새 객체, 시도 lease 가 지난 시각)
        OrderEventRelay restarted = new OrderEventRelay(processor, sender, properties, Clock.offset(clock, properties.sendTimeout()));
        OrderEventRelay.Run second = restarted.runDue();
        assertThat(second.published()).isEqualTo(1);

        // 5. consumer 가 두 record 를 모두 처리·ack 한 뒤 DB 의 상품별 구매 사실
        E2eKafka.awaitConsumedAll(GROUP, TOPIC);

        List<ConsumerRecord<String, String>> records = E2eKafka.recordsOf(TOPIC, eventId);
        assertThat(records).hasSizeGreaterThanOrEqualTo(2);
        assertThat(records).extracting(ConsumerRecord::offset).doesNotHaveDuplicates();
        assertThat(records).extracting(ConsumerRecord::value).containsOnly((String) outbox.get(0).get("payload"));
        assertThat(records.get(0).partition()).isEqualTo(stopping.acked.get(0).partition());
        assertThat(records.get(0).offset()).isEqualTo(stopping.acked.get(0).offset());
        Map<TopicPartition, Long> committed = E2eKafka.committed(GROUP, TOPIC);
        for (ConsumerRecord<String, String> record : records) {
            assertThat(committed.get(new TopicPartition(TOPIC, record.partition())))
                .as("consumer 가 %d@%d 를 받아 처리 뒤 ack 했다", record.partition(), record.offset())
                .isGreaterThan(record.offset());
        }

        List<Map<String, Object>> finalOutbox = db.outboxOfOrder(orderId);
        assertThat(finalOutbox).extracting(row -> row.get("event_id"), row -> row.get("status"), row -> row.get("attempt_count"))
            .containsExactly(tuple(eventId, "PUBLISHED", 2));
        assertThat(db.processedOfOrder(orderId)).extracting(row -> row.get("event_id")).containsExactly(eventId);
        assertThat(db.activityOfOrder(orderId)).extracting(row -> row.get("product_id"), row -> row.get("quantity"))
            .containsExactly(tuple(7L, 2), tuple(8L, 1));
        assertThat(db.order(orderId)).isEqualTo(orderRow);
        assertThat(db.ledgerOfOrder(orderId)).isEqualTo(ledger);
        assertThat(db.stockOf(product7.getId())).isEqualTo(8);
        assertThat(db.stockOf(product8.getId())).isEqualTo(9);
        assertThat(db.pointOf(user.getId())).as("PG 주문은 포인트를 차감하지 않는다").isEqualTo(BALANCE);
        assertThat(pg.requests()).hasSize(1);
        assertThat(pg.requests().stream().map(r -> FakePaymentGateway.transactionKeyOf(r.orderId())).distinct()).hasSize(1);

        log.info("[W7 관찰: E2E] orderId={}, eventId={}, transactionKey={}, outbox={}, broker={}, committed={}, 완료 기록={}, 구매 활동={}, 재고(7/8)={}/{}, 포인트={}, PG create={}",
            orderId, eventId, orderRow.get("transaction_key"), finalOutbox.get(0).get("status") + " attempt=" + finalOutbox.get(0).get("attempt_count"),
            records.stream().map(r -> r.partition() + "@" + r.offset()).toList(), committed, db.processedOfOrder(orderId).size(),
            db.activityOfOrder(orderId), db.stockOf(product7.getId()), db.stockOf(product8.getId()), db.pointOf(user.getId()), pg.requests().size());
    }
}
