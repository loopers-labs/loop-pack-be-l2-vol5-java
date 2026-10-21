package com.loopers.interfaces.api.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.brand.BrandApplicationService;
import com.loopers.application.order.OrderApplicationService;
import com.loopers.application.payment.PaymentGatewayRegistry;
import com.loopers.application.payment.port.PaymentCreateCommand;
import com.loopers.application.payment.port.PaymentCreateResult;
import com.loopers.application.payment.port.PaymentGateway;
import com.loopers.application.payment.port.PaymentTransactionsLookup;
import com.loopers.application.product.ProductApplicationService;
import com.loopers.infrastructure.payment.TestPgPaymentGateway;
import com.loopers.infrastructure.payment.TestPgProperties;
import com.loopers.infrastructure.payment.TestPgServer;
import com.loopers.infrastructure.payment.TestPgServer.CreateBehavior;
import com.loopers.infrastructure.user.UserJpaEntity;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.pg.MutablePaymentClock;
import com.loopers.support.pg.TestPgFixture;
import com.loopers.utils.DatabaseCleanUp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
class OrderPaymentFallbackIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private MutablePaymentClock clock;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private BrandApplicationService brands;
    @Autowired private ProductApplicationService products;
    @Autowired private OrderApplicationService orders;
    @Autowired private UserJpaRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;
    @MockBean(name = "testPaymentGatewayRegistry") private PaymentGatewayRegistry registry;

    private TestPgServer main;
    private TestPgServer sub;
    private boolean mainClosed;
    private boolean subClosed;
    private NamedGateway subGateway;
    private long productId;
    private long orderId;
    private String key;

    @BeforeEach
    void prepare() throws Exception {
        clock.set(Instant.parse("2026-10-21T00:00:00Z"));
        main = new TestPgServer("135135", Duration.ofMillis(1500));
        sub = new TestPgServer("135135", Duration.ofMillis(1500));
        NamedGateway mainGateway = gateway("main", main);
        subGateway = gateway("sub", sub);
        PaymentGatewayRegistry configured = new PaymentGatewayRegistry(List.of(mainGateway, subGateway), List.of("main", "sub"));
        when(registry.inPriorityOrder()).thenReturn(configured.inPriorityOrder());
        when(registry.get(anyString())).thenAnswer(invocation -> configured.get(invocation.getArgument(0)));
        users.save(new UserJpaEntity(1));
        long brandId = brands.create("브랜드").id().value();
        productId = products.create(brandId, "상품", 1000, 5).id();
        orderId = orders.create(1, List.of(new OrderApplicationService.ItemRequest(productId, 2))).id();
        key = UUID.randomUUID().toString();
    }

    private NamedGateway gateway(String name, TestPgServer server) {
        return new NamedGateway(name, new TestPgPaymentGateway(new TestPgProperties(server.baseUrl(), "135135",
            "http://127.0.0.1/api/v1/payments/callback", Duration.ofSeconds(1), Duration.ofMillis(500)), objectMapper));
    }

    @AfterEach
    void clean() throws Exception {
        if (!mainClosed) {
            main.close();
        }
        if (!subClosed) {
            sub.close();
        }
        jdbc.update("delete from order_items");
        cleanup.truncateAllTables();
    }

    private void closeMain() throws Exception {
        main.close();
        mainClosed = true;
    }

    @Test
    @DisplayName("main 미발송이면 sub 성공으로 확정하고 현재 PG를 sub로 저장하며 재고를 한 번 소비한다")
    void switchesOnlyAfterNotSent() throws Exception {
        closeMain();
        sub.createBehavior(CreateBehavior.ACCEPT_SUCCESS);
        confirm().andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CONFIRMED"));
        scene("CONFIRMED", 3, "sub", "SUCCESS", 0, 1, 0, 1);
    }

    @Test
    @DisplayName("sub 전환 후 callback과 관리자 재조회는 결제 기록의 sub만 조회하고 create를 추가하지 않는다")
    void verifiesCurrentProviderAfterSwitch() throws Exception {
        closeMain();
        sub.createBehavior(CreateBehavior.ACCEPT_PENDING);
        confirm().andExpect(status().isAccepted());
        String transactionKey = sub.transactions().get(0).transactionKey();
        sub.changeStatus(transactionKey, "SUCCESS");
        mvc.perform(post("/api/v1/payments/callback").contentType(MediaType.APPLICATION_JSON)
                .content("{\"transactionKey\":\"" + transactionKey + "\",\"orderId\":\""
                    + String.format("LP-ORD-%010d", orderId) + "\",\"amount\":2000,\"status\":\"SUCCESS\"}"))
            .andExpect(status().isOk());
        mvc.perform(post("/api-admin/v1/orders/{id}/payment/reconcile", orderId)
                .with(csrf()).with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CONFIRMED"));
        scene("CONFIRMED", 3, "sub", "SUCCESS", 0, 1, 0, 1);
        assertThat(main.lookupCalls()).isZero();
        assertThat(sub.lookupCalls()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from payment_anomalies", Long.class)).isZero();
    }

    @Test
    @DisplayName("sub 전환 뒤 자동 재조회는 현재 sub만 조회해 확정하고 create 재발송 없이 스케줄을 지운다")
    void automaticallyVerifiesCurrentSubProvider() throws Exception {
        closeMain();
        sub.createBehavior(CreateBehavior.ACCEPT_PENDING);
        confirm().andExpect(status().isAccepted());
        sub.changeStatus(sub.transactions().get(0).transactionKey(), "SUCCESS");
        clock.advance(Duration.ofSeconds(5));

        orders.reconcileDuePayments();

        scene("CONFIRMED", 3, "sub", "SUCCESS", 0, 1, 0, 1);
        assertThat(main.lookupCalls()).isZero();
        assertThat(sub.lookupCalls()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from payment_lookup_schedules", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from payment_anomalies", Long.class)).isZero();
    }

    @Test
    @DisplayName("main 미발송 뒤 sub 응답이 유실되면 sub 결제 대기를 유지하고 추가 발송하지 않는다")
    void stopsAfterSubUnknown() throws Exception {
        closeMain();
        sub.createBehavior(CreateBehavior.DELAY_AFTER_CREATE);
        confirm().andExpect(status().isAccepted());
        confirm().andExpect(status().isAccepted());
        scene("PAYMENT_PENDING", 3, "sub", "UNKNOWN", 0, 1, 0, 1);
    }

    @Test
    @DisplayName("main 응답이 유실되면 sub로 전환하지 않고 main 결제 대기를 유지한다")
    void doesNotSwitchAfterUnknown() throws Exception {
        main.createBehavior(CreateBehavior.DELAY_AFTER_CREATE);
        confirm().andExpect(status().isAccepted());
        assertThat(sub.createCalls()).isZero();
        scene("PAYMENT_PENDING", 3, "main", "UNKNOWN", 1, 0, 1, 0);
    }

    @Test
    @DisplayName("main 효과 없는 거절이면 sub로 전환하지 않고 재고를 반환한다")
    void doesNotSwitchAfterRejection() throws Exception {
        main.createBehavior(CreateBehavior.REJECT_BEFORE_CREATE);
        confirm().andExpect(status().isOk());
        scene("PAYMENT_FAILED", 5, "main", "REJECTED_NO_EFFECT", 0, 0, 1, 0);
    }

    @Test
    @DisplayName("모든 PG 미발송이면 결제 실패로 종결하고 재고를 한 번 반환한다")
    void failsWhenAllNotSent() throws Exception {
        closeMain();
        sub.close();
        subClosed = true;
        confirm().andExpect(status().isOk());
        confirm().andExpect(status().isOk());
        scene("PAYMENT_FAILED", 5, "sub", "ALL_NOT_SENT", 0, 0, 0, 0);
    }

    @Test
    @DisplayName("모든 PG 미발송으로 종결된 주문의 callback이 sub에서 거래 0건을 조회해도 이상을 남기지 않는다")
    void doesNotRecordNoTransactionAfterAllNotSent() throws Exception {
        closeMain();
        sub.close();
        subClosed = true;
        confirm().andExpect(status().isOk());
        scene("PAYMENT_FAILED", 5, "sub", "ALL_NOT_SENT", 0, 0, 0, 0);

        // 연결 실패 뒤 PG가 다시 가용해진 장면이다. 조회 어댑터만 새 fixture 주소로 연결한다.
        sub = new TestPgServer("135135", Duration.ofMillis(1500));
        subClosed = false;
        when(registry.get("sub")).thenReturn(gateway("sub", sub));
        mvc.perform(post("/api/v1/payments/callback").contentType(MediaType.APPLICATION_JSON)
                .content("{\"transactionKey\":\"UNVERIFIED-KEY\",\"orderId\":\""
                    + String.format("LP-ORD-%010d", orderId) + "\",\"amount\":2000,\"status\":\"FAILED\"}"))
            .andExpect(status().isOk());

        scene("PAYMENT_FAILED", 5, "sub", "ALL_NOT_SENT", 0, 0, 0, 0);
        assertThat(main.lookupCalls()).isZero();
        assertThat(sub.lookupCalls()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from payment_anomalies", Long.class)).isZero();
    }

    @Test
    @DisplayName("sub 전환 저장 직후 발송 전 중단되면 sub 발송 시작을 유지하고 같은 키 재요청은 재발송하지 않는다")
    void doesNotResendAfterSwitchCommit() throws Exception {
        closeMain();
        subGateway.crash = true;
        assertThatThrownBy(() -> orders.confirm(1, orderId, TestPgFixture.command(key)))
            .isInstanceOf(IllegalStateException.class).hasMessage("발송 전 중단");
        confirm().andExpect(status().isAccepted());
        scene("PAYMENT_PENDING", 3, "sub", "SEND_STARTED", 0, 0, 0, 0);
    }

    private ResultActions confirm() throws Exception {
        return mvc.perform(post("/api/v1/orders/{id}/confirm", orderId).header("X-USER-ID", "1")
            .contentType(MediaType.APPLICATION_JSON).content("{\"paymentAttemptKey\":\"" + key
                + "\",\"cardType\":\"" + TestPgFixture.CARD_TYPE + "\",\"cardNo\":\"" + TestPgFixture.CARD_NO + "\"}"));
    }

    private void scene(String status, int stock, String provider, String pgStatus, int mainTransactions, int subTransactions,
        int mainCreates, int subCreates) {
        assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class, orderId)).isEqualTo(status);
        assertThat(jdbc.queryForObject("select stock from products where id = ?", Integer.class, productId)).isEqualTo(stock);
        assertThat(jdbc.queryForMap("select provider_id, provider_status from payment_records where order_id = ?", orderId))
            .containsEntry("provider_id", provider).containsEntry("provider_status", pgStatus);
        assertThat(main.transactions()).hasSize(mainTransactions);
        assertThat(sub.transactions()).hasSize(subTransactions);
        assertThat(main.createCalls()).isEqualTo(mainCreates);
        assertThat(sub.createCalls()).isEqualTo(subCreates);
    }

    private static class NamedGateway implements PaymentGateway {
        private final String name;
        private final PaymentGateway delegate;
        private boolean crash;

        NamedGateway(String name, PaymentGateway delegate) {
            this.name = name;
            this.delegate = delegate;
        }

        @Override
        public String providerId() {
            return name;
        }

        @Override
        public PaymentCreateResult create(PaymentCreateCommand command) {
            if (crash) {
                throw new IllegalStateException("발송 전 중단");
            }
            return delegate.create(command);
        }

        @Override
        public PaymentTransactionsLookup verifyOrder(String providerOrderId, String storedTransactionKey) {
            return delegate.verifyOrder(providerOrderId, storedTransactionKey);
        }
    }
}
