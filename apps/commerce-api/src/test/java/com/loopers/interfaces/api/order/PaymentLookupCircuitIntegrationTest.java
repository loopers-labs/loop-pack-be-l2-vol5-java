package com.loopers.interfaces.api.order;

import com.loopers.application.brand.BrandApplicationService;
import com.loopers.application.order.OrderApplicationService;
import com.loopers.application.payment.PaymentGatewayRegistry;
import com.loopers.application.payment.port.PaymentGateway;
import com.loopers.application.payment.port.PaymentLookupScheduleRepository;
import com.loopers.application.product.ProductApplicationService;
import com.loopers.infrastructure.payment.PaymentCircuitBreakers;
import com.loopers.infrastructure.payment.PaymentLookupCircuitProperties;
import com.loopers.infrastructure.payment.TestPgServer;
import com.loopers.infrastructure.user.UserJpaEntity;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.pg.MutablePaymentClock;
import com.loopers.support.pg.TestPgFixture;
import com.loopers.utils.DatabaseCleanUp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PaymentLookupScheduleIntegrationTest.TimeConfig.class)
class PaymentLookupCircuitIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-21T00:00:00Z");
    @Autowired private MockMvc mvc;
    @Autowired private BrandApplicationService brands;
    @Autowired private ProductApplicationService products;
    @Autowired private OrderApplicationService orders;
    @Autowired private PaymentLookupScheduleRepository schedules;
    @Autowired private MutablePaymentClock clock;
    @Autowired private TestPgFixture pg;
    @Autowired private UserJpaRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;
    @MockBean(name = "testPaymentGatewayRegistry") private PaymentGatewayRegistry registry;
    private TestPgServer server;
    private long orderId;
    private long productId;

    @BeforeEach
    void prepare() {
        clock.set(NOW);
        server = pg.start();
        PaymentGateway gateway = new PaymentCircuitBreakers(clock,
            new PaymentLookupCircuitProperties(20, 10, 50, Duration.ofSeconds(30), 3)).wrap(pg.newClient());
        when(registry.inPriorityOrder()).thenReturn(List.of(gateway));
        when(registry.get(anyString())).thenReturn(gateway);
        users.save(new UserJpaEntity(1));
        long brandId = brands.create("브랜드").id().value();
        productId = products.create(brandId, "상품", 1000, 5).id();
        orderId = orders.create(1, List.of(new OrderApplicationService.ItemRequest(productId, 2))).id();
        orders.confirm(1, orderId, TestPgFixture.command());
    }

    @AfterEach
    void clean() throws Exception {
        pg.close();
        jdbc.update("delete from order_items");
        cleanup.truncateAllTables();
    }

    @Test
    @DisplayName("회로 열림 reconcile은 503이며 PG 조회·주문·재고·스케줄·이상 기록을 바꾸지 않는다")
    void manualNotCalledIs503AndDoesNotChangeState() throws Exception {
        openCircuit();
        PaymentLookupScheduleRepository.Schedule beforeSchedule = schedules.findByOrderId(orderId).orElseThrow();
        List<Map<String, Object>> beforeAnomalies = jdbc.queryForList("select * from payment_anomalies order by id");
        Map<String, Object> beforePayment = jdbc.queryForMap("select * from payment_records where order_id = ?", orderId);

        mvc.perform(post(reconcilePath()).with(csrf()).with(user("admin").roles("ADMIN")))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.meta.errorCode").value("Service Unavailable"));
        mvc.perform(post("/api/v1/orders/{id}/payment/reconcile", orderId).header("X-USER-ID", "1"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.meta.errorCode").value("Service Unavailable"));

        scene(3, 1, 10, 5);
        assertThat(schedules.findByOrderId(orderId)).contains(beforeSchedule);
        assertThat(jdbc.queryForList("select * from payment_anomalies order by id")).isEqualTo(beforeAnomalies);
        assertThat(jdbc.queryForMap("select * from payment_records where order_id = ?", orderId)).isEqualTo(beforePayment);
    }

    @Test
    @DisplayName("실제 PG 500 reconcile은 200과 현재 상태를 반환하고 조회 실패 이상을 기록한다")
    void actualLookupFailureRemains200() throws Exception {
        server.lookupBehavior(TestPgServer.LookupBehavior.ERROR_500);
        PaymentLookupScheduleRepository.Schedule before = schedules.findByOrderId(orderId).orElseThrow();
        mvc.perform(post(reconcilePath()).with(csrf()).with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PAYMENT_PENDING"));
        scene(3, 1, 1, 1);
        assertThat(schedules.findByOrderId(orderId)).contains(before);
    }

    @Test
    @DisplayName("열린 회로의 자동 조회는 횟수·이상을 늘리지 않고 시각만 미루며 30초 뒤 실제 탐색을 집계한다")
    void schedulerDoesNotCountNotCalledAndProbesLater() {
        openCircuit();
        clock.advance(Duration.ofSeconds(5));
        orders.reconcileDuePayments();
        scene(3, 1, 10, 5);
        assertThat(schedules.findByOrderId(orderId)).contains(new PaymentLookupScheduleRepository.Schedule(
            orderId, NOW.plusSeconds(10), 0, NOW, NOW.plusSeconds(5)));
        orders.reconcileDuePayments();
        scene(3, 1, 10, 5);
        clock.advance(Duration.ofSeconds(5));
        orders.reconcileDuePayments();
        scene(3, 1, 10, 5);
        assertThat(schedules.findByOrderId(orderId).orElseThrow().actualAttempts()).isZero();
        clock.set(NOW.plusSeconds(31));
        orders.reconcileDuePayments();
        scene(3, 1, 11, 6);
        assertThat(schedules.findByOrderId(orderId)).contains(new PaymentLookupScheduleRepository.Schedule(
            orderId, NOW.plusSeconds(41), 1, NOW, NOW.plusSeconds(31)));
    }

    @Test
    @DisplayName("열린 회로의 callback은 다른 본문 키여도 200과 로그만 남기고 저장 상태를 바꾸지 않는다")
    void callbackNotCalledIs200WithoutAnomalies() throws Exception {
        openCircuit();
        PaymentLookupScheduleRepository.Schedule before = schedules.findByOrderId(orderId).orElseThrow();
        mvc.perform(post("/api/v1/payments/callback").contentType(MediaType.APPLICATION_JSON)
                .content("{\"transactionKey\":\"FORGED-KEY\",\"orderId\":\"" + String.format("LP-ORD-%010d", orderId)
                    + "\",\"amount\":2000,\"status\":\"SUCCESS\"}"))
            .andExpect(status().isOk());
        scene(3, 1, 10, 5);
        assertThat(schedules.findByOrderId(orderId)).contains(before);
        assertThat(jdbc.queryForObject("select count(*) from payment_anomalies where kind = 'CALLBACK_KEY_MISMATCH'", Long.class))
            .isZero();
    }

    @Test
    @DisplayName("조회 회로가 열려도 새 주문 confirm은 create를 보내고 스케줄을 저장한다")
    void confirmsNewOrderEvenWhenLookupCircuitIsOpen() {
        openCircuit();
        orderId = orders.create(1, List.of(new OrderApplicationService.ItemRequest(productId, 1))).id();
        assertThat(orders.confirm(1, orderId, TestPgFixture.command()).status()).isEqualTo("PAYMENT_PENDING");
        scene(2, 2, 10, 5);
        assertThat(schedules.findByOrderId(orderId).orElseThrow().actualAttempts()).isZero();
    }

    private void openCircuit() {
        for (int index = 0; index < 5; index++) {
            orders.reconcilePayment(orderId);
        }
        server.lookupBehavior(TestPgServer.LookupBehavior.ERROR_500);
        for (int index = 0; index < 5; index++) {
            orders.reconcilePayment(orderId);
        }
        scene(3, 1, 10, 5);
    }

    private String reconcilePath() {
        return "/api-admin/v1/orders/" + orderId + "/payment/reconcile";
    }

    private void scene(int stock, int creates, int lookups, long failures) {
        assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class, orderId)).isEqualTo("PAYMENT_PENDING");
        assertThat(jdbc.queryForObject("select stock from products where id = ?", Integer.class, productId)).isEqualTo(stock);
        assertThat(jdbc.queryForMap("select provider_id, provider_status from payment_records where order_id = ?", orderId))
            .containsEntry("provider_id", "test-pg").containsEntry("provider_status", "PENDING");
        assertThat(server.createCalls()).isEqualTo(creates);
        assertThat(server.lookupCalls()).isEqualTo(lookups);
        assertThat(jdbc.queryForObject("select count(*) from payment_anomalies where kind = 'LOOKUP_FAILED'", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select occurrences from payment_anomalies where kind = 'LOOKUP_FAILED'", Long.class)).isEqualTo(failures);
    }
}
