package com.loopers.order.interfaces;

import com.loopers.order.application.OrderPaymentTransactionService;
import com.loopers.order.domain.Order;
import com.loopers.order.domain.PaymentGateway;
import com.loopers.order.infrastructure.payment.toss.TossPgFixture;
import com.loopers.product.domain.Product;
import com.loopers.support.fixture.CommerceFixture;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.user.domain.User;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static com.loopers.support.http.ApiHttp.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlTestContainersConfig.class)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("[W10-MONITORING] 결제 재조정 상관 구조화 로그")
class PaymentReconcileLogHttpTest {

    private static final String RECONCILE = "/api/v1/orders/{orderId}/payment/reconcile";

    @Autowired private MockMvc mockMvc;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private OrderPaymentTransactionService paymentTransactionService;
    @Autowired private DatabaseCleanUp databaseCleanUp;
    @MockitoBean private PaymentGateway paymentGateway;

    private CommerceFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new CommerceFixture(entityManager, transactionManager);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        fixture.truncateRemainingTables();
    }

    @Test
    @DisplayName("미확정 조회 경계에서 orderId·attemptKey·PG 결과·내부 결과를 구조화 필드로 남긴다")
    void recordsCorrelatedUnresolvedPaymentBoundary(CapturedOutput output) throws Exception {
        User buyer = fixture.user();
        Product product = fixture.product(fixture.brand("log-" + UUID.randomUUID()), "상품", 2_000L, 2);
        Order order = fixture.draftOrder(buyer, fixture.item(product, 1));
        String paymentKey = "logging-payment-" + UUID.randomUUID();
        String attemptKey = "logging-attempt-" + UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            var plan = paymentTransactionService.prepare(buyer.getId(), order.getId(), paymentKey, attemptKey);
            plan.order().getPayment().recordResult(new PaymentGateway.Unknown());
            entityManager.flush();
        });
        when(paymentGateway.findByPaymentKey(paymentKey)).thenReturn(new PaymentGateway.Unknown());

        mockMvc.perform(post(RECONCILE, order.getId()).with(customer(buyer)).with(csrf()))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.paymentStatus").value("UNKNOWN"));

        assertThat(output.getOut())
            .containsAnyOf("\"orderId\":" + order.getId(), "orderId=" + order.getId())
            .containsAnyOf("\"paymentAttemptKey\":\"" + attemptKey + "\"", "paymentAttemptKey=" + attemptKey)
            .containsAnyOf("\"pgOutcome\":\"unknown\"", "pgOutcome=unknown")
            .containsAnyOf("\"applicationOutcome\":\"unchanged\"", "applicationOutcome=unchanged")
            .containsAnyOf("\"observedAt\":", "observedAt=");
        assertThat(output.getOut()).doesNotContain(TossPgFixture.SECRET_KEY);
    }
}
