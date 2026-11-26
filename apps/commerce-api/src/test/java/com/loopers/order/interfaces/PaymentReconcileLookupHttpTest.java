package com.loopers.order.interfaces;

import com.loopers.order.application.OrderPaymentTransactionService;
import com.loopers.order.domain.Order;
import com.loopers.order.domain.PaymentGateway;
import com.loopers.order.domain.PaymentStatus;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static com.loopers.support.http.ApiHttp.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlTestContainersConfig.class)
@DisplayName("[C-13] 미확인 조회 결과는 결제 실패로 오해하지 않는다")
class PaymentReconcileLookupHttpTest {

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
    @DisplayName("PG 거래 404는 미확정으로 반환하고 주문·예약 상태를 보존한다")
    void preservesUnknownWhenProviderHasNoTransaction() throws Exception {
        User buyer = fixture.user();
        Product product = fixture.product(fixture.brand("lookup-" + UUID.randomUUID()), "상품", 2_000L, 3);
        Order order = fixture.draftOrder(buyer, fixture.item(product, 1));
        String paymentKey = "lookup-payment-" + UUID.randomUUID();
        String attemptKey = "lookup-attempt-" + UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            var plan = paymentTransactionService.prepare(buyer.getId(), order.getId(), paymentKey, attemptKey);
            plan.order().getPayment().recordResult(new PaymentGateway.Unknown());
            entityManager.flush();
        });
        when(paymentGateway.findByPaymentKey(paymentKey)).thenReturn(
            new PaymentGateway.ProviderError(404, "NOT_FOUND", "거래를 찾을 수 없습니다."));

        mockMvc.perform(post(RECONCILE, order.getId()).with(customer(buyer)).with(csrf()))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.meta.result").value("PENDING"))
            .andExpect(jsonPath("$.data.paymentStatus").value("UNKNOWN"));

        entityManager.clear();
        Order stored = entityManager.find(Order.class, order.getId());
        assertThat(stored.getStatus().name()).isEqualTo("DRAFT");
        assertThat(stored.getPayment().status()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(stored.getStockReservationStatus().name()).isEqualTo("RESERVED");
        verify(paymentGateway, never()).approve(any());
        verify(paymentGateway).findByPaymentKey(paymentKey);
    }
}
