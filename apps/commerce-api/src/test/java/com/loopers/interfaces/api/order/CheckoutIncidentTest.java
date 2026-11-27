package com.loopers.interfaces.api.order;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.facade.OrderPaymentFacade;
import com.loopers.domain.brand.entity.Brand;
import com.loopers.domain.payment.entity.Payment;
import com.loopers.domain.product.entity.Product;
import com.loopers.domain.user.entity.User;
import com.loopers.infrastructure.payment.client.TestPgPaymentGateway;
import com.loopers.support.pg.TestPgServer;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 10주차 사고 실험 (설계 D-206). PG 는 SUCCESS 인데 생성 응답을 잃어 내부가 UNKNOWN 으로 남는 사고를
 * 같은 DB · 같은 테스트 PG 상태로 baseline → fault 순서로 만듦. 단계 사이에 DB · PG 를 초기화하지 않음
 */
@AutoConfigureObservability(tracing = false)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"management.server.port=0", "payment.metrics.unresolved.refresh-ms=200"}
)
@AutoConfigureMockMvc
class CheckoutIncidentTest {

    private static final String USER_ID_HEADER = "X-USER-ID";
    private static final int ORDERS_PER_SET = 10;
    private static final int INITIAL_STOCK = 30;
    private static final long PRICE = 1_000L;
    private static final Duration GAUGE_TIMEOUT = Duration.ofSeconds(5);
    private static final Pattern UNRESOLVED_LINE = Pattern.compile("commerce_payment_unresolved\\{([^}]*)} (\\S+)");

    /** 모든 주문이 2026-08-17 주에 처음 확정되도록 고정함 (D-207) */
    private static final IncidentClock CLOCK = new IncidentClock(Instant.parse("2026-08-19T12:00:00Z"));

    @TestBean(name = "clock")
    private Clock clock;

    static Clock clock() {
        return CLOCK;
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @LocalManagementPort
    private int managementPort;

    private final TestPgServer pg = TestPgServer.shared();
    private final HttpClient http = HttpClient.newHttpClient();
    private final Logger gatewayLogger = (Logger) LoggerFactory.getLogger(TestPgPaymentGateway.class);
    private final Logger facadeLogger = (Logger) LoggerFactory.getLogger(OrderPaymentFacade.class);
    private final ListAppender<ILoggingEvent> paymentLogs = new ListAppender<>();
    private User user;
    private Product product;

    @BeforeEach
    void setUp() {
        pg.reset();
        CLOCK.set(Instant.parse("2026-08-19T12:00:00Z"));
        paymentLogs.start();
        gatewayLogger.addAppender(paymentLogs);
        facadeLogger.addAppender(paymentLogs);
        transactionTemplate.executeWithoutResult(status -> {
            user = new User();
            entityManager.persist(user);
            Brand brand = new Brand("사고 실험 브랜드", null);
            entityManager.persist(brand);
            product = new Product(brand, "사고 실험 상품", PRICE);
            product.changeStock(INITIAL_STOCK);
            entityManager.persist(product);
        });
    }

    @AfterEach
    void tearDown() {
        // 테스트가 켠 주입만 풂. 거래 · 증거는 이 테스트 안에서 지우지 않음
        pg.stopDroppingResponses();
        gatewayLogger.detachAppender(paymentLogs);
        facadeLogger.detachAppender(paymentLogs);
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("PG 성공 응답을 잃은 장애 주문 3건만 UNKNOWN 으로 남고, health 는 UP 이며 미종결 지표가 3 을 보인다.")
    @Test
    void reproducesLostSuccessResponse() throws Exception {
        // baseline: 응답 유실 없이 정상 10건
        assertThat(pg.dropTargets()).as("baseline 에서는 응답 유실이 꺼져 있어야 함").isEmpty();
        List<Long> baseline = createOrders();
        for (Long orderId : baseline) {
            assertThat(confirm(orderId).at("/data/paymentStatus").asText()).isEqualTo("PENDING");
            completeAtPg(orderId);
            reconcile(orderId);
        }

        Map<Long, OrderState> baselineStates = states(baseline);
        assertAll(
            () -> assertThat(baselineStates.values()).allSatisfy(state -> assertThat(state.confirmed()).isTrue()),
            () -> assertThat(baseline).allSatisfy(this::assertSingleSuccessTransaction),
            () -> assertThat(pg.transactions()).hasSize(10),
            () -> assertThat(pg.createCount()).isEqualTo(10),
            () -> assertThat(stock()).isEqualTo(INITIAL_STOCK - 10),
            () -> assertThat(healthStatus()).isEqualTo("UP"),
            () -> assertThat(awaitUnresolved("UNKNOWN", 0)).isZero()
        );
        CLOCK.advance(Duration.ofMinutes(5));

        // fault: 별도 집합 10건 중 실제 ID 로 고른 3건만 거래 저장 뒤 응답 유실 (D-198)
        List<Long> fault = createOrders();
        List<Long> dropped = fault.subList(0, 3);
        List<Long> normal = fault.subList(3, ORDERS_PER_SET);
        Set<String> droppedProviderOrderIds = dropped.stream().map(Payment::providerOrderIdOf).collect(Collectors.toSet());
        pg.willDropResponseFor(droppedProviderOrderIds);
        try {
            for (Long orderId : fault) {
                String expected = dropped.contains(orderId) ? "UNKNOWN" : "PENDING";
                assertThat(confirm(orderId).at("/data/paymentStatus").asText()).isEqualTo(expected);
            }
        } finally {
            // 새 실험 입력을 멈춘 뒤에는 유실을 끔
            pg.stopDroppingResponses();
        }
        int lookupsBeforeSettle = pg.lookupCount();
        for (Long orderId : normal) {
            completeAtPg(orderId);
            reconcile(orderId);
        }
        // 장애 3건은 PG 에 SUCCESS 만 저장하고 내부 조회는 보류함. callback 을 보내는 경로가 없어 끌 것도 없음
        dropped.forEach(this::completeAtPg);

        Map<Long, OrderState> faultStates = states(fault);
        assertAll(
            () -> assertThat(normal).allSatisfy(orderId -> assertThat(faultStates.get(orderId).confirmed()).isTrue()),
            () -> assertThat(dropped).allSatisfy(orderId -> assertThat(faultStates.get(orderId))
                .isEqualTo(new OrderState(orderId, "PAYMENT_PENDING", "UNKNOWN", "RESERVED", null))),
            () -> assertThat(fault).allSatisfy(this::assertSingleSuccessTransaction),
            () -> assertThat(pg.transactions()).hasSize(20),
            () -> assertThat(pg.createCount()).isEqualTo(20),
            () -> assertThat(pg.duplicateCreates()).isEmpty(),
            // UNKNOWN 3건도 예약을 유지함
            () -> assertThat(stock()).isEqualTo(INITIAL_STOCK - 20),
            () -> assertThat(pg.lookupCount() - lookupsBeforeSettle).as("조회는 정상 7건의 reconcile 뿐").isEqualTo(normal.size()),
            () -> assertThat(healthStatus()).isEqualTo("UP"),
            () -> assertThat(awaitUnresolved("UNKNOWN", 3)).isEqualTo(3),
            () -> assertThat(awaitUnresolved("PENDING", 0)).isZero(),
            () -> assertThat(unknownCreateLogs()).containsExactlyInAnyOrderElementsOf(droppedProviderOrderIds)
        );
    }

    /** 내부 상태 한 줄. 거래 키는 생성 응답이나 조회로 알게 됐을 때만 있음 */
    private record OrderState(Long orderId, String orderStatus, String paymentStatus, String reservationStatus, String transactionKey) {
        boolean confirmed() {
            return "CONFIRMED".equals(orderStatus) && "SUCCESS".equals(paymentStatus)
                && "CONSUMED".equals(reservationStatus) && transactionKey != null;
        }
    }

    private List<Long> createOrders() throws Exception {
        List<Long> orderIds = new ArrayList<>();
        String body = "{\"items\": [{\"productId\": " + product.getId() + ", \"quantity\": 1}]}";
        for (int i = 0; i < ORDERS_PER_SET; i++) {
            orderIds.add(postAs("/api/v1/orders", body).at("/data/id").asLong());
        }
        return List.copyOf(orderIds);
    }

    private JsonNode confirm(Long orderId) throws Exception {
        return postAs("/api/v1/orders/" + orderId + "/confirm",
            "{\"paymentAttemptKey\": \"incident-" + orderId + "\", \"cardType\": \"TEST\", \"cardNo\": \"0000-0000-0000-0000\"}");
    }

    private void reconcile(Long orderId) throws Exception {
        postAs("/api/v1/orders/" + orderId + "/payment/reconcile", "");
    }

    /** 수락(PENDING)된 거래를 PG 쪽에서 최종 SUCCESS 로 만듦 */
    private void completeAtPg(Long orderId) {
        List<TestPgServer.Transaction> transactions = pg.transactionsOf(Payment.providerOrderIdOf(orderId));
        assertThat(transactions).as("주문 %d 의 거래", orderId).hasSize(1);
        pg.complete(transactions.get(0).transactionKey(), "SUCCESS");
    }

    private void assertSingleSuccessTransaction(Long orderId) {
        String providerOrderId = Payment.providerOrderIdOf(orderId);
        assertThat(pg.transactionsOf(providerOrderId))
            .singleElement()
            .satisfies(transaction -> {
                assertThat(transaction.status()).isEqualTo("SUCCESS");
                assertThat(transaction.amount()).isEqualTo(PRICE);
            });
        assertThat(pg.createCountOf(providerOrderId)).isEqualTo(1);
    }

    /** 메모리 값이 아니라 매번 DB 에서 다시 읽음 */
    private Map<Long, OrderState> states(List<Long> orderIds) {
        return jdbc.query("""
                select o.id, o.status as order_status, p.status as payment_status, p.reservation_status, p.transaction_key
                from orders o join payment p on p.order_id = o.id
                where o.id in (:ids)
                """,
            Map.of("ids", orderIds),
            (rs, rowNum) -> new OrderState(rs.getLong("id"), rs.getString("order_status"), rs.getString("payment_status"),
                rs.getString("reservation_status"), rs.getString("transaction_key")))
            .stream()
            .collect(Collectors.toMap(OrderState::orderId, state -> state));
    }

    private int stock() {
        return jdbc.queryForObject("select stock from product where id = :id", Map.of("id", product.getId()), Integer.class);
    }

    private String healthStatus() throws Exception {
        HttpResponse<String> response = management("/actuator/health");
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readTree(response.body()).path("status").asText();
    }

    /** gauge 는 갱신 주기만큼 늦으므로 유한한 시간 안에 기대값이 될 때까지 봄 (D-202) */
    private double awaitUnresolved(String paymentStatus, double expected) throws Exception {
        long deadline = System.nanoTime() + GAUGE_TIMEOUT.toNanos();
        double value;
        do {
            value = unresolved(paymentStatus);
            if (value == expected) {
                return value;
            }
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        return value;
    }

    private double unresolved(String paymentStatus) throws Exception {
        Matcher matcher = UNRESOLVED_LINE.matcher(management("/actuator/prometheus").body());
        while (matcher.find()) {
            if (matcher.group(1).contains("status=\"" + paymentStatus + "\"")) {
                return Double.parseDouble(matcher.group(2));
            }
        }
        throw new AssertionError("prometheus 응답에 " + paymentStatus + " 미종결 지표가 없음");
    }

    private List<String> unknownCreateLogs() {
        Pattern pattern = Pattern.compile("PG 생성 결과\\. providerOrderId=(\\S+) .*result=UNKNOWN");
        return paymentLogs.list.stream()
            .map(ILoggingEvent::getFormattedMessage)
            .map(pattern::matcher)
            .filter(Matcher::find)
            .map(matcher -> matcher.group(1))
            .toList();
    }

    private HttpResponse<String> management(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + path)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode postAs(String path, String body) throws Exception {
        String response = mvc.perform(post(path)
                .header(USER_ID_HEADER, String.valueOf(user.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().is2xxSuccessful())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    /** 단계마다 앞으로만 옮기는 시계. DB 정밀도에 맞춰 마이크로초 단위로 둠 */
    private static final class IncidentClock extends Clock {
        private volatile Instant instant;

        IncidentClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
