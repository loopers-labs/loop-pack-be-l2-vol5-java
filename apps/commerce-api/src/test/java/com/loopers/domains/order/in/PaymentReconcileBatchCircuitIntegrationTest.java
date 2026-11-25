package com.loopers.domains.order.in;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domains.brand.app.BrandApplicationService;
import com.loopers.domains.order.app.OrderApplicationService;
import com.loopers.domains.payment.app.PaymentGatewayRegistry;
import com.loopers.domains.payment.app.PaymentLookupNotCalledException;
import com.loopers.domains.payment.app.port.PaymentGateway;
import com.loopers.domains.payment.out.PaymentCircuitBreakers;
import com.loopers.domains.payment.out.TestPgServer;
import com.loopers.domains.product.app.ProductApplicationService;
import com.loopers.domains.user.out.UserJpaEntity;
import com.loopers.domains.user.out.UserJpaRepository;
import com.loopers.support.pg.MutablePaymentClock;
import com.loopers.support.pg.TestPgFixture;
import com.loopers.utils.DatabaseCleanUp;
import java.time.Instant;
import java.util.ArrayList;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 시작 전 중단은 운영 조회 회로(PaymentCircuitBreakers 빈)의 상태를 본다. 테스트 레지스트리는 회로를 감싸지 않으므로
// 이 클래스만 운영 빈으로 테스트 PG 게이트웨이를 감싼다. 열린 회로는 컨텍스트에 남으므로 테스트마다 컨텍스트를 버린다.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@SpringBootTest
@AutoConfigureMockMvc
@Import(PaymentLookupScheduleIntegrationTest.TimeConfig.class)
class PaymentReconcileBatchCircuitIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-21T00:00:00Z");
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private BrandApplicationService brands;
    @Autowired private ProductApplicationService products;
    @Autowired private OrderApplicationService orders;
    @Autowired private PaymentCircuitBreakers paymentCircuitBreakers;
    @Autowired private MutablePaymentClock clock;
    @Autowired private TestPgFixture pg;
    @Autowired private UserJpaRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;
    @MockBean(name = "testPaymentGatewayRegistry") private PaymentGatewayRegistry registry;
    private TestPgServer server;
    private long productId;

    @BeforeEach
    void prepare() {
        clock.set(NOW);
        server = pg.start();
        PaymentGateway gateway = paymentCircuitBreakers.wrap(pg.newClient());
        when(registry.inPriorityOrder()).thenReturn(List.of(gateway));
        when(registry.get(anyString())).thenReturn(gateway);
        users.save(new UserJpaEntity(1));
        long brandId = brands.create("브랜드").id();
        productId = products.create(brandId, "상품", 1000, 10).id();
    }

    @AfterEach
    void clean() throws Exception {
        pg.close();
        jdbc.update("delete from order_items");
        cleanup.truncateAllTables();
    }

    @Test
    @DisplayName("조회 회로가 열려 있으면 503이고 목록의 주문을 하나도 조회·확정하지 않으며 create도 보내지 않는다")
    void doesNotStartWhileLookupCircuitIsOpen() throws Exception {
        long opener = pendingOrder();
        openCircuit(opener);
        long listed = pendingOrder();
        server.lookupBehavior(TestPgServer.LookupBehavior.NORMAL);
        server.changeStatus(transactionKey(listed), "SUCCESS");
        long creates = server.createCalls();
        long lookups = server.lookupCalls();
        List<Map<String, Object>> paymentsBefore = jdbc.queryForList("select * from payment_records order by order_id");
        List<Map<String, Object>> anomaliesBefore = jdbc.queryForList("select * from payment_anomalies order by id");

        mvc.perform(post("/api-admin/v1/payments/reconcile-batch").contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderIds\":[" + listed + "," + opener + "]}")
                .with(csrf()).with(user("admin").roles("ADMIN")))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.meta.errorCode").value("Service Unavailable"));

        assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class, listed))
            .isEqualTo("PAYMENT_PENDING");
        assertThat(jdbc.queryForList("select * from payment_records order by order_id")).isEqualTo(paymentsBefore);
        assertThat(jdbc.queryForList("select * from payment_anomalies order by id")).isEqualTo(anomaliesBefore);
        assertThat(server.createCalls()).isEqualTo(creates);
        assertThat(server.lookupCalls()).isEqualTo(lookups);
    }

    // 조회 회로 기본 설정(대기 30초, 반열림 시험 3회)으로 확정 1·조회 실패 2를 시험하면 다시 열린다.
    @Test
    @DisplayName("회로가 열린 채여도 대기 시간이 지났으면 시작하고, 도중에 회로가 다시 열려 PG를 부르지 못한 주문을 연속 실패로 세어 중단한다")
    void startsAfterOpenDurationAndCountsNotCalledAsConsecutiveFailure() throws Exception {
        long opener = pendingOrder();
        openCircuit(opener);
        server.lookupBehavior(TestPgServer.LookupBehavior.NORMAL);
        long resolved = pendingOrder();
        server.changeStatus(transactionKey(resolved), "SUCCESS");
        long firstFailed = pendingOrder();
        server.lookupErrorFor(providerOrderId(firstFailed));
        long secondFailed = pendingOrder();
        server.lookupErrorFor(providerOrderId(secondFailed));
        long notCalled = pendingOrder();
        server.changeStatus(transactionKey(notCalled), "SUCCESS");
        long skipped = pendingOrder();
        server.changeStatus(transactionKey(skipped), "SUCCESS");
        clock.set(NOW.plusSeconds(31));
        long lookups = server.lookupCalls();

        String response = mvc.perform(post("/api-admin/v1/payments/reconcile-batch").contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderIds\":[" + resolved + "," + firstFailed + "," + secondFailed + "," + notCalled + ","
                    + skipped + "]}")
                .with(csrf()).with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        JsonNode data = objectMapper.readTree(response).get("data");
        assertThat(data.get("stopReason").asText()).isEqualTo("CONSECUTIVE_FAILURES");
        List<String> rows = new ArrayList<>();
        data.get("results").forEach(item -> rows.add(item.get("outcome").asText() + "/" + item.get("assessment").asText()
            + "/" + item.get("reason").asText()));
        assertThat(rows).containsExactly("RESOLVED/VERIFIED/null", "STOPPED/UNCERTAIN/LOOKUP_FAILED",
            "STOPPED/UNCERTAIN/LOOKUP_FAILED", "STOPPED/NOT_CALLED/LOOKUP_NOT_CALLED", "SKIPPED/null/CONSECUTIVE_FAILURES");
        assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class, resolved))
            .isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class, notCalled))
            .isEqualTo("PAYMENT_PENDING");
        assertThat(server.lookupCalls() - lookups).as("반열림 시험 3회만 PG를 부른다").isEqualTo(3);
    }

    private long pendingOrder() {
        long orderId = orders.create(1, List.of(new OrderApplicationService.ItemRequest(productId, 1))).id();
        orders.confirm(1, orderId, TestPgFixture.command());
        return orderId;
    }

    // 창 20·최소 10·실패율 50%: 정상 5회 뒤 실패 5회로 열고, 다음 수동 조회가 PG 호출 없이 거절되는지로 열림을 확인한다.
    private void openCircuit(long orderId) {
        for (int index = 0; index < 5; index++) {
            orders.reconcilePayment(orderId);
        }
        server.lookupBehavior(TestPgServer.LookupBehavior.ERROR_500);
        for (int index = 0; index < 5; index++) {
            orders.reconcilePayment(orderId);
        }
        assertThatThrownBy(() -> orders.reconcilePayment(orderId)).isInstanceOf(PaymentLookupNotCalledException.class);
        assertThat(server.lookupCalls()).isEqualTo(10);
    }

    private static String providerOrderId(long orderId) {
        return String.format("LP-ORD-%010d", orderId);
    }

    private String transactionKey(long orderId) {
        String providerOrderId = providerOrderId(orderId);
        return server.transactions().stream()
            .filter(transaction -> transaction.orderId().equals(providerOrderId))
            .findFirst().orElseThrow().transactionKey();
    }
}
