package com.loopers.domains.order.in;

import com.loopers.domains.brand.app.BrandApplicationService;
import com.loopers.domains.order.app.OrderApplicationService;
import com.loopers.domains.order.app.OrderOutboxRelay;
import com.loopers.domains.payment.out.TestPgServer;
import com.loopers.domains.product.app.ProductApplicationService;
import com.loopers.domains.user.out.UserJpaEntity;
import com.loopers.domains.user.out.UserJpaRepository;
import com.loopers.support.kafka.OrderEventsProbe;
import com.loopers.support.kafka.OrderEventsTestConfig.ControllableOrderConfirmedPublisher;
import com.loopers.support.kafka.OrderEventsTestConfig.FailingKafkaTemplate;
import com.loopers.support.kafka.OrderEventsTestConfig.FailingOrderConfirmedOutbox;
import com.loopers.support.kafka.OrderEventsTestConfig.FailingPaymentRecordRepository;
import com.loopers.support.kafka.OrderEventsTestConfig.PublishedOrderEvents;
import com.loopers.support.kafka.OrderEventsTestConfig.ScriptedMessageSender;
import com.loopers.support.kafka.OrderEventsTestConfig;
import com.loopers.support.pg.MutablePaymentClock;
import com.loopers.support.pg.TestPgFixture;
import com.loopers.utils.DatabaseCleanUp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

// 주문 확정 이벤트 통합 테스트가 같은 컨텍스트를 쓰도록 설정·topic을 이 부모 하나에 둔다(같은 DynamicPropertySource면 컨텍스트 재사용).
// 실제 주문 API → 테스트 PG 서버 → 결과 반영 경로로 확정하고, 단일 partition 고유 topic을 broker에서 직접 읽는다.
@SpringBootTest
@AutoConfigureMockMvc
@Import({PaymentLookupScheduleIntegrationTest.TimeConfig.class, OrderEventsTestConfig.class})
abstract class OrderEventsIntegrationSupport {
    static final String TOPIC = "order-events-it-" + UUID.randomUUID();
    static final String REQUEST_TOPIC = "order-event-republish-requests-it-" + UUID.randomUUID();
    static final String REQUEST_GROUP = "commerce-api-republish-it-" + UUID.randomUUID();
    static final Instant NOW = Instant.parse("2026-10-29T01:00:00Z");

    @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers;
    @Autowired MockMvc mvc;
    @Autowired BrandApplicationService brands;
    @Autowired ProductApplicationService products;
    @Autowired OrderApplicationService orders;
    @Autowired UserJpaRepository users;
    @Autowired TestPgFixture pg;
    @Autowired MutablePaymentClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired DatabaseCleanUp cleanup;
    @Autowired ControllableOrderConfirmedPublisher publisher;
    @Autowired PublishedOrderEvents publishedEvents;
    @Autowired FailingPaymentRecordRepository failingPayments;
    @Autowired FailingKafkaTemplate failingKafka;
    @Autowired FailingOrderConfirmedOutbox failingOutbox;
    @Autowired ScriptedMessageSender sender;
    @Autowired OrderOutboxRelay relay;

    TestPgServer server;
    OrderEventsProbe probe;
    long from;
    long first;
    long second;

    @DynamicPropertySource
    static void orderEventsTopic(DynamicPropertyRegistry registry) {
        registry.add("commerce.kafka.topics.order-events", () -> TOPIC);
        registry.add("commerce.kafka.topics.order-events-partitions", () -> 1);
        registry.add("commerce.kafka.topics.order-event-republish-requests", () -> REQUEST_TOPIC);
        registry.add("commerce.kafka.topics.order-event-republish-requests-partitions", () -> 1);
        registry.add("commerce.kafka.groups.order-event-republish", () -> REQUEST_GROUP);
    }

    @BeforeEach
    void prepareOrderEvents() {
        clock.set(NOW);
        server = pg.start();
        users.save(new UserJpaEntity(1));
        long brand = brands.create("브랜드").id();
        first = products.create(brand, "첫 상품", 2000, 5).id();
        second = products.create(brand, "둘째 상품", 3000, 5).id();
        publisher.reset();
        publishedEvents.reset();
        failingPayments.disarm();
        failingOutbox.disarm();
        sender.reset();
        failingKafka.fail(FailingKafkaTemplate.Failure.NONE);
        probe = new OrderEventsProbe(bootstrapServers, TOPIC);
        from = probe.mark();
    }

    @AfterEach
    void cleanOrderEvents() throws InterruptedException {
        probe.close();
        pg.close();
        failingPayments.disarm();
        failingOutbox.disarm();
        sender.reset();
        failingKafka.fail(FailingKafkaTemplate.Failure.NONE);
        publisher.reset();
        jdbc.update("delete from order_items");
        cleanup.truncateAllTables();
    }

    long newOrder() {
        return orders.create(1, List.of(new OrderApplicationService.ItemRequest(first, 2),
            new OrderApplicationService.ItemRequest(second, 1))).id();
    }

    ResultActions confirm(long orderId, String attemptKey) throws Exception {
        String body = "{\"paymentAttemptKey\":\"" + attemptKey + "\",\"cardType\":\"" + TestPgFixture.CARD_TYPE
            + "\",\"cardNo\":\"" + TestPgFixture.CARD_NO + "\"}";
        return mvc.perform(post("/api/v1/orders/{id}/confirm", orderId).header("X-USER-ID", "1")
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    ResultActions callback(long orderId, String status) throws Exception {
        String providerOrderId = providerOrderId(orderId);
        return mvc.perform(post("/api/v1/payments/callback").contentType(MediaType.APPLICATION_JSON)
            .content("{\"transactionKey\":\"" + transactionKey(orderId) + "\",\"orderId\":\"" + providerOrderId
                + "\",\"status\":\"" + status + "\"}"));
    }

    ResultActions reconcile(long orderId) throws Exception {
        return mvc.perform(post("/api/v1/orders/{id}/payment/reconcile", orderId).header("X-USER-ID", "1"));
    }

    String transactionKey(long orderId) {
        return server.transactions().stream()
            .filter(transaction -> transaction.orderId().equals(providerOrderId(orderId)))
            .findFirst().orElseThrow().transactionKey();
    }

    static String providerOrderId(long orderId) {
        return String.format("LP-ORD-%010d", orderId);
    }

    static String eventId(long orderId) {
        return "order-confirmed:v1:" + orderId;
    }

    // 확정 경로는 broker에 직접 보내지 않고 relay가 보낸다(D95). relay 회차를 한 번 돌린 뒤 broker를 읽는다.
    List<String> eventIdsAfterRelay() {
        relay.runOnce();
        return eventIdsOnBroker();
    }

    // producer에 남은 비동기 send를 flush로 끝낸 뒤 지금 끝 위치까지 읽는다. relay는 broker 확인까지 기다리므로
    // 그 뒤에 보내는 쪽이 없어 "없음"을 판단할 수 있다.
    List<String> eventIdsOnBroker() {
        failingKafka.flush();
        return probe.readThrough(from).stream().map(OrderEventsProbe::eventId).toList();
    }

    void assertNoEvent(long orderId) {
        assertThat(eventIdsAfterRelay()).doesNotContain(eventId(orderId));
    }

    Map<String, Object> outboxRow(long orderId) {
        return jdbc.queryForMap("select id, status, attempt_count, row_failure_count, last_error, next_attempt_at, published_at,"
            + " message_key, payload from order_outbox where order_id = ?", orderId);
    }

    String orderStatus(long orderId) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, orderId);
    }

    int stock(long productId) {
        return jdbc.queryForObject("select stock from products where id = ?", Integer.class, productId);
    }
}
