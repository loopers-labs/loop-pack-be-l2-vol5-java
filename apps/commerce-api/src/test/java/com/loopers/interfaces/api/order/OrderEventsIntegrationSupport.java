package com.loopers.interfaces.api.order;

import com.loopers.application.brand.BrandApplicationService;
import com.loopers.application.order.OrderApplicationService;
import com.loopers.application.product.ProductApplicationService;
import com.loopers.infrastructure.payment.TestPgServer;
import com.loopers.infrastructure.payment.TestPgServer.CreateBehavior;
import com.loopers.infrastructure.user.UserJpaEntity;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.kafka.OrderEventsProbe;
import com.loopers.support.kafka.OrderEventsTestConfig;
import com.loopers.support.kafka.OrderEventsTestConfig.ControllableOrderConfirmedPublisher;
import com.loopers.support.kafka.OrderEventsTestConfig.FailingKafkaTemplate;
import com.loopers.support.kafka.OrderEventsTestConfig.FailingPaymentRecordRepository;
import com.loopers.support.kafka.OrderEventsTestConfig.PublishedOrderEvents;
import com.loopers.support.pg.MutablePaymentClock;
import com.loopers.support.pg.TestPgFixture;
import com.loopers.utils.DatabaseCleanUp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
        long brand = brands.create("브랜드").id().value();
        first = products.create(brand, "첫 상품", 2000, 5).id();
        second = products.create(brand, "둘째 상품", 3000, 5).id();
        publisher.reset();
        publishedEvents.reset();
        failingPayments.disarm();
        failingKafka.fail(FailingKafkaTemplate.Failure.NONE);
        probe = new OrderEventsProbe(bootstrapServers, TOPIC);
        from = probe.mark();
    }

    @AfterEach
    void cleanOrderEvents() throws InterruptedException {
        probe.close();
        pg.close();
        failingPayments.disarm();
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

    // 다른 주문을 실제 경로로 확정해 같은 producer·같은 partition에 경계 record를 남긴다.
    String confirmBoundaryOrder() throws Exception {
        long boundary = newOrder();
        server.createBehavior(CreateBehavior.ACCEPT_SUCCESS);
        confirm(boundary, UUID.randomUUID().toString()).andExpect(status().isOk());
        return eventId(boundary);
    }

    List<String> eventIdsThroughBoundary() throws Exception {
        List<ConsumerRecord<String, String>> records = probe.readThrough(from, confirmBoundaryOrder());
        return records.stream().map(OrderEventsProbe::eventId).toList();
    }

    void assertNoEvent(long orderId) throws Exception {
        assertThat(eventIdsThroughBoundary()).doesNotContain(eventId(orderId));
    }

    String orderStatus(long orderId) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, orderId);
    }

    int stock(long productId) {
        return jdbc.queryForObject("select stock from products where id = ?", Integer.class, productId);
    }
}
