package com.loopers.interfaces.api.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.brand.BrandFacade;
import com.loopers.application.order.OrderEventRelay;
import com.loopers.application.product.ProductFacade;
import com.loopers.domain.common.Quantity;
import com.loopers.domain.payment.PaymentRepository;
import com.loopers.domain.product.Price;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.support.kafka.TopicRecords;
import com.loopers.support.pg.PgFixture;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderEventFlowE2ETest {

    private static final String ORDERS = "/api/v1/orders";
    private static final String ADMIN_ORDERS = "/api-admin/v1/orders";
    private static final String TOPIC = KafkaTestContainersConfig.ORDER_EVENTS;
    private static final Long USER = 1L;
    private static final PgFixture PG = PgFixture.shared();
    private static final ParameterizedTypeReference<ApiResponse<OrderV1Dto.OrderResponse>> ORDER =
        new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<OrderV1Dto.ReconcileResponse>> RECONCILE =
        new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<OrderAdminV1Dto.EventRecoveryResponse>> RECOVERY =
        new ParameterizedTypeReference<>() {};

    private final TestRestTemplate testRestTemplate;
    private final OrderEventRelay relay;
    private final BrandFacade brandFacade;
    private final ProductFacade productFacade;
    private final PaymentRepository paymentRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final DatabaseCleanUp databaseCleanUp;

    private Long coat;
    private Long shirt;

    @Autowired
    OrderEventFlowE2ETest(TestRestTemplate testRestTemplate, OrderEventRelay relay, BrandFacade brandFacade,
                          ProductFacade productFacade, PaymentRepository paymentRepository,
                          JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, DatabaseCleanUp databaseCleanUp) {
        this.testRestTemplate = testRestTemplate;
        this.relay = relay;
        this.brandFacade = brandFacade;
        this.productFacade = productFacade;
        this.paymentRepository = paymentRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.databaseCleanUp = databaseCleanUp;
    }

    @BeforeEach
    void setUp() {
        PG.reset();
        Long brandId = brandFacade.register("무신사", "패션 플랫폼").getId();
        coat = productFacade.register(brandId, "코트", Price.of(3_000)).getId();
        shirt = productFacade.register(brandId, "셔츠", Price.of(1_000)).getId();
        productFacade.adjustStock(coat, Quantity.of(10));
        productFacade.adjustStock(shirt, Quantity.of(10));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", String.valueOf(USER));
        return headers;
    }

    private Long confirmedThroughApi() {
        Object body = Map.of("lines", List.of(
            Map.of("productId", shirt, "quantity", 1),
            Map.of("productId", coat, "quantity", 2)));
        Long orderId = testRestTemplate.exchange(ORDERS, HttpMethod.POST, new HttpEntity<>(body, headers()), ORDER)
            .getBody().data().id();
        testRestTemplate.exchange(ORDERS + "/" + orderId + "/confirm", HttpMethod.POST,
            new HttpEntity<>(Map.of("paymentAttemptKey", "attempt-" + orderId, "cardType", "TEST",
                "cardNo", "0000-0000-0000-0000"), headers()), Object.class);
        String providerOrderId = paymentRepository.findByOrderId(orderId).orElseThrow().getProviderOrderId().value();
        PG.transactionsOf(providerOrderId).forEach(transaction ->
            PG.changeStatus(transaction.transactionKey(), PgFixture.Status.SUCCESS));
        ResponseEntity<ApiResponse<OrderV1Dto.ReconcileResponse>> reconciled = testRestTemplate.exchange(
            ORDERS + "/" + orderId + "/payment/reconcile", HttpMethod.POST, new HttpEntity<>(null, headers()), RECONCILE);
        assertThat(reconciled.getBody().data().orderStatus()).isEqualTo("CONFIRMED");
        return orderId;
    }

    private Map<String, Object> outboxRow(Long orderId) {
        return jdbcTemplate.queryForMap(
            "select event_id, record_key, payload, status from order_event_outbox where order_id = ?", orderId);
    }

    private JsonNode json(byte[] payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("API 로 확정하면 broker 에는 아무것도 가지 않고 outbox 에 PENDING 으로 남는다. relay 가 돌면 그 행을 한 번 보내고 PUBLISHED 로 바꾼다")
    void confirmationReachesTopicOnlyThroughRelay() {
        Long[] orderId = new Long[1];

        List<ConsumerRecord<String, byte[]>> beforeRelay =
            TopicRecords.during(TOPIC, () -> orderId[0] = confirmedThroughApi());

        Map<String, Object> pending = outboxRow(orderId[0]);
        assertThat(beforeRelay).isEmpty();
        assertThat(pending.get("status")).isEqualTo("PENDING");

        List<ConsumerRecord<String, byte[]>> relayed = TopicRecords.during(TOPIC, () -> relay.relayOnce(10));

        assertThat(relayed).singleElement().satisfies(record -> {
            assertThat(record.key()).isEqualTo(String.valueOf(orderId[0]));
            assertThat(record.value()).isEqualTo((byte[]) pending.get("payload"));
            JsonNode message = json(record.value());
            assertThat(message.get("eventId").asText()).isEqualTo("order-confirmed:v1:" + orderId[0]);
            assertThat(message.get("items")).hasSize(2);
            assertThat(message.get("items").get(0).get("productId").asLong()).isEqualTo(coat);
            assertThat(message.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
            assertThat(message.get("items").get(1).get("productId").asLong()).isEqualTo(shirt);
            assertThat(message.get("items").get(1).get("quantity").asInt()).isEqualTo(1);
        });
        assertThat(outboxRow(orderId[0]).get("status")).isEqualTo("PUBLISHED");
        assertThat(TopicRecords.during(TOPIC, () -> relay.relayOnce(10))).isEmpty();
    }

    @Test
    @DisplayName("outbox 를 잃은 확정 주문을 관리자 API 로 복구하면 relay 가 처음과 같은 key · payload 를 보낸다. 다시 복구해도 더 보내지 않는다")
    void recoveredEventReachesTopicWithSameIdentity() {
        Long orderId = confirmedThroughApi();
        byte[] original = (byte[]) outboxRow(orderId).get("payload");
        jdbcTemplate.update("delete from order_event_outbox where order_id = ?", orderId);
        int pgRequests = PG.receivedRequests().size();

        ResponseEntity<ApiResponse<OrderAdminV1Dto.EventRecoveryResponse>> recovered = recover(orderId);
        List<ConsumerRecord<String, byte[]>> relayed = TopicRecords.during(TOPIC, () -> relay.relayOnce(10));

        assertThat(recovered.getStatusCode().value()).isEqualTo(200);
        assertThat(recovered.getBody().data().result()).isEqualTo("APPENDED");
        assertThat(relayed).singleElement().satisfies(record -> {
            assertThat(record.key()).isEqualTo(String.valueOf(orderId));
            assertThat(record.value()).isEqualTo(original);
        });

        ResponseEntity<ApiResponse<OrderAdminV1Dto.EventRecoveryResponse>> again = recover(orderId);

        assertThat(again.getBody().data().result()).isEqualTo("ALREADY_PRESENT");
        assertThat(TopicRecords.during(TOPIC, () -> relay.relayOnce(10))).isEmpty();
        assertThat(PG.receivedRequests()).hasSize(pgRequests);
    }

    private ResponseEntity<ApiResponse<OrderAdminV1Dto.EventRecoveryResponse>> recover(Long orderId) {
        return testRestTemplate.withBasicAuth("admin", "local-only-not-a-secret").exchange(
            ADMIN_ORDERS + "/" + orderId + "/confirmed-event/recovery", HttpMethod.POST, HttpEntity.EMPTY, RECOVERY);
    }
}
