package com.loopers.application.order.facade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.brand.entity.Brand;
import com.loopers.domain.order.entity.Order;
import com.loopers.domain.order.entity.OrderConfirmedOutbox;
import com.loopers.domain.order.enums.OrderOutboxStatus;
import com.loopers.domain.order.enums.OrderStatus;
import com.loopers.domain.order.gateway.OrderConfirmedSender;
import com.loopers.domain.payment.entity.Payment;
import com.loopers.domain.product.entity.Product;
import com.loopers.domain.user.entity.User;
import com.loopers.support.kafka.OrderEventsTopicReader;
import com.loopers.support.pg.TestPgServer;
import com.loopers.support.process.BootJarProcess;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 7주차 설계 7 의 E2E. 주문 API 로 확정한 주문을 relay 가 "broker 확인 뒤 저장 전"에 멈춘 채 한 번 보내고, 재시작한 relay 가 같은 eventId 를 다시 보냄.
 * 따로 띄운 실제 commerce-streamer(bootJar) 가 두 record 를 모두 받아도 구매 활동은 한 번만 남는지 같은 MySQL 에서 확인함.
 * 다른 테스트의 record 와 섞이지 않게 이 테스트만의 topic 을 씀
 */
@SpringBootTest(properties = "order-event.topic=" + OrderOutboxDeliveryE2ETest.TOPIC)
@AutoConfigureMockMvc
class OrderOutboxDeliveryE2ETest {

    static final String TOPIC = "order-events-outbox-e2e";
    private static final String USER_ID_HEADER = "X-USER-ID";
    private static final Duration STREAMER_START_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration CONSUME_TIMEOUT = Duration.ofSeconds(30);
    private static final List<String> STREAMER_TABLES = List.of("processed_order_confirmation", "product_purchase_activity");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderOutboxRelay relay;

    @MockitoSpyBean
    private OrderOutboxRelaySteps steps;

    @Autowired
    private OrderConfirmedSender sender;

    @Autowired
    private OrderOutboxRelayProperties relayProperties;

    @Autowired
    private Clock clock;

    @Autowired
    private OrderEventsTopicReader topicReader;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Value("${commerce-streamer.boot-jar}")
    private String streamerBootJar;

    private final TestPgServer pg = TestPgServer.shared();
    private BootJarProcess streamer;
    private User user;
    private Product first;
    private Product second;

    @BeforeEach
    void setUp() {
        pg.reset();
        transactionTemplate.executeWithoutResult(status -> {
            user = new User();
            entityManager.persist(user);
            Brand brand = new Brand("브랜드", null);
            entityManager.persist(brand);
            first = new Product(brand, "첫 번째 상품", 1_000L);
            first.changeStock(10);
            entityManager.persist(first);
            second = new Product(brand, "두 번째 상품", 2_500L);
            second.changeStock(5);
            entityManager.persist(second);
        });
    }

    @AfterEach
    void tearDown() {
        if (streamer != null) {
            streamer.close();
        }
        databaseCleanUp.truncateAllTables();
        // streamer 의 테이블은 이 context 의 엔티티가 아니라 정리 대상에 없음
        transactionTemplate.executeWithoutResult(status ->
            STREAMER_TABLES.forEach(table -> entityManager.createNativeQuery("TRUNCATE TABLE `" + table + "`").executeUpdate()));
    }

    @DisplayName("broker 확인 뒤 저장 전에 멈춰 같은 eventId 가 두 record 로 가도, 실제 streamer 는 처리 기록 · 구매 활동을 한 번만 남기고 재고 · PG 는 그대로다.")
    @Test
    void deliversOnce_whenRepublishedAfterStop() throws Exception {
        // arrange: 실제 streamer 를 같은 MySQL · broker 에 띄움. 테이블은 streamer 가 update 로 만듦
        streamer = BootJarProcess.start("commerce-streamer", Path.of(streamerBootJar), streamerArgs());
        streamer.awaitLine(line -> line.contains("Started CommerceStreamerApplication"), STREAMER_START_TIMEOUT);

        Long orderId = createOrder(first, 2, second, 1);
        confirm(orderId);
        pg.complete(onlyTransactionKeyOf(orderId), "SUCCESS");
        reconcile(orderId);
        OrderConfirmedOutbox pending = outboxOf(orderId);
        Map<Long, Integer> stockAfterConfirm = Map.of(first.getId(), stockOf(first), second.getId(), stockOf(second));
        Map<TopicPartition, Long> before = topicReader.endOffsets();
        doThrow(new IllegalStateException("확인 뒤 저장 전 중단")).doCallRealMethod().when(steps).markPublished(any(), any());

        // act: 첫 relay 는 broker 확인을 받은 뒤 PUBLISHED 저장 전에 멈추고, lease 뒤 새 relay 가 다시 보냄
        assertThrows(IllegalStateException.class, () -> relay.runOnce());
        OrderConfirmedOutbox stopped = outboxOf(orderId);
        Clock afterLease = Clock.offset(clock, relayProperties.lease().plus(Duration.ofSeconds(1)));
        new OrderOutboxRelay(steps, sender, afterLease, relayProperties).runOnce();
        streamer.awaitCount(consumed(pending.getEventId()), 2, CONSUME_TIMEOUT);

        // assert
        List<ConsumerRecord<String, String>> records = topicReader.recordsSince(before);
        OrderConfirmedOutbox published = outboxOf(orderId);
        assertAll(
            () -> assertThat(statusOf(orderId)).isEqualTo(OrderStatus.CONFIRMED),
            () -> assertThat(pg.transactionsOf(Payment.providerOrderIdOf(orderId))).hasSize(1),
            () -> assertThat(stopped.getStatus()).isEqualTo(OrderOutboxStatus.PENDING),
            () -> assertThat(published.getEventId()).isEqualTo("order-confirmed:v1:" + orderId),
            () -> assertThat(published.getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED),
            () -> assertThat(outboxCount()).isEqualTo(1),
            () -> assertThat(records).hasSize(2),
            () -> assertThat(records).extracting(ConsumerRecord::key).containsOnly(String.valueOf(orderId)),
            () -> assertThat(records).extracting(ConsumerRecord::value).containsOnly(pending.getPayload()),
            () -> assertThat(records.get(0).offset()).isNotEqualTo(records.get(1).offset()),
            () -> assertThat(streamer.count(consumed(pending.getEventId()).and(line -> line.contains("result=RECORDED ")))).isEqualTo(1),
            () -> assertThat(streamer.count(consumed(pending.getEventId()).and(line -> line.contains("result=ALREADY_RECORDED ")))).isEqualTo(1),
            () -> assertThat(rows("select event_id, order_id from processed_order_confirmation"))
                .containsExactly(tuple(pending.getEventId(), orderId)),
            () -> assertThat(rows("select product_id, quantity from product_purchase_activity where order_id = " + orderId + " order by product_id"))
                .containsExactly(tuple(first.getId(), 2L), tuple(second.getId(), 1L)),
            () -> assertThat(Map.of(first.getId(), stockOf(first), second.getId(), stockOf(second))).isEqualTo(stockAfterConfirm),
            () -> assertThat(stockAfterConfirm).isEqualTo(Map.of(first.getId(), 8, second.getId(), 4))
        );
    }

    private List<String> streamerArgs() {
        return List.of(
            "--spring.profiles.active=test",
            "--server.port=0",
            "--management.server.port=0",
            // 같은 DB 의 api 테이블을 지우지 않도록 create 대신 update
            "--spring.jpa.hibernate.ddl-auto=update",
            "--datasource.mysql-jpa.main.jdbc-url=" + System.getProperty("datasource.mysql-jpa.main.jdbc-url"),
            "--datasource.mysql-jpa.main.username=" + System.getProperty("datasource.mysql-jpa.main.username"),
            "--datasource.mysql-jpa.main.password=" + System.getProperty("datasource.mysql-jpa.main.password"),
            "--spring.kafka.bootstrap-servers=" + System.getProperty("spring.kafka.bootstrap-servers"),
            "--datasource.redis.database=0",
            "--datasource.redis.master.host=" + System.getProperty("datasource.redis.master.host"),
            "--datasource.redis.master.port=" + System.getProperty("datasource.redis.master.port"),
            "--datasource.redis.replicas[0].host=" + System.getProperty("datasource.redis.replicas[0].host"),
            "--datasource.redis.replicas[0].port=" + System.getProperty("datasource.redis.replicas[0].port"),
            "--order-event.topic=" + TOPIC,
            "--order-event.consumer-group=purchase-activity-outbox-e2e"
        );
    }

    private static Predicate<String> consumed(String eventId) {
        return line -> line.contains("주문 확정 이벤트 소비") && line.contains("eventId=" + eventId + " ");
    }

    private Long createOrder(Product firstProduct, int firstQuantity, Product secondProduct, int secondQuantity) throws Exception {
        String body = "{\"items\": [{\"productId\": " + firstProduct.getId() + ", \"quantity\": " + firstQuantity + "}, "
            + "{\"productId\": " + secondProduct.getId() + ", \"quantity\": " + secondQuantity + "}]}";
        String response = postAs("/api/v1/orders", body);
        return objectMapper.readTree(response).at("/data/id").asLong();
    }

    private void confirm(Long orderId) throws Exception {
        postAs("/api/v1/orders/" + orderId + "/confirm",
            "{\"paymentAttemptKey\": \"attempt-" + orderId + "\", \"cardType\": \"TEST\", \"cardNo\": \"0000-0000-0000-0000\"}");
    }

    private void reconcile(Long orderId) throws Exception {
        postAs("/api/v1/orders/" + orderId + "/payment/reconcile", "");
    }

    private String postAs(String path, String body) throws Exception {
        return mvc.perform(post(path)
                .header(USER_ID_HEADER, String.valueOf(user.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().is2xxSuccessful())
            .andReturn().getResponse().getContentAsString();
    }

    private String onlyTransactionKeyOf(Long orderId) {
        List<TestPgServer.Transaction> transactions = pg.transactionsOf(Payment.providerOrderIdOf(orderId));
        assertThat(transactions).hasSize(1);
        return transactions.get(0).transactionKey();
    }

    private OrderConfirmedOutbox outboxOf(Long orderId) {
        return transactionTemplate.execute(status -> entityManager
            .createQuery("select o from OrderConfirmedOutbox o where o.orderId = :orderId", OrderConfirmedOutbox.class)
            .setParameter("orderId", orderId)
            .getSingleResult());
    }

    private OrderStatus statusOf(Long orderId) {
        return transactionTemplate.execute(status -> entityManager.find(Order.class, orderId).getStatus());
    }

    private int stockOf(Product product) {
        return transactionTemplate.execute(status -> entityManager.find(Product.class, product.getId()).getStock());
    }

    private long outboxCount() {
        return transactionTemplate.execute(status ->
            ((Number) entityManager.createNativeQuery("select count(*) from order_confirmed_outbox").getSingleResult()).longValue());
    }

    @SuppressWarnings("unchecked")
    private List<Tuple> rows(String sql) {
        return transactionTemplate.execute(status -> ((List<Object[]>) entityManager.createNativeQuery(sql).getResultList()).stream()
            .map(row -> tuple(row[0] instanceof Number number ? number.longValue() : row[0], row[1] instanceof Number number ? number.longValue() : row[1]))
            .toList());
    }
}
