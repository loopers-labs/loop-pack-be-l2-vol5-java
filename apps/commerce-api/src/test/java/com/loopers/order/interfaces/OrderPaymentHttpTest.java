package com.loopers.order.interfaces;

import com.loopers.order.domain.PaymentGateway;
import com.loopers.product.domain.Product;
import com.loopers.support.fixture.CommerceFixture;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.user.domain.User;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.OffsetDateTime;

import static com.loopers.support.http.ApiHttp.customer;
import static com.loopers.support.http.ApiHttp.failure;
import static com.loopers.support.http.ApiHttp.success;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlTestContainersConfig.class)
@DisplayName("주문 PG 결제 클라이언트 응답")
class OrderPaymentHttpTest {

    private static final String CONFIRM = "/api/v1/orders/{orderId}/confirm";
    private static final OffsetDateTime APPROVED_AT = OffsetDateTime.parse("2026-10-21T15:00:00+09:00");

    @Autowired private MockMvc mockMvc;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
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

    @DisplayName("[C-10] 동기 결제 결과를 클라이언트 응답으로 변환한다.")
    @Nested
    class SynchronousResult {

        @DisplayName("승인 성공이면 200과 CONFIRMED 주문 및 결제 결과를 반환한다.")
        @Test
        void returnsConfirmedOrderWhenPaymentSucceeds() throws Exception {
            User buyer = fixture.user();
            Product product = fixture.product(fixture.brand("PG-" + System.nanoTime()), "상품", 2_000L, 5);
            var order = fixture.draftOrder(buyer, fixture.item(product, 2));
            when(paymentGateway.approve(any())).thenAnswer(invocation -> {
                PaymentGateway.ApprovalRequest request = invocation.getArgument(0);
                return new PaymentGateway.Approved(
                    request.paymentKey(), request.providerOrderId(), request.amount(), APPROVED_AT);
            });

            mockMvc.perform(confirm(buyer, order.getId()))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.payment.amount").value(4_000))
                .andExpect(jsonPath("$.data.payment.paidAt").isNotEmpty());

            verify(paymentGateway).approve(any());
        }

        @DisplayName("승인 거절이면 422 PAYMENT_FAILED를 반환한다.")
        @Test
        void returnsPaymentFailedWhenProviderRejectsPayment() throws Exception {
            User buyer = fixture.user();
            Product product = fixture.product(fixture.brand("PG-" + System.nanoTime()), "상품", 2_000L, 5);
            var order = fixture.draftOrder(buyer, fixture.item(product, 2));
            when(paymentGateway.approve(any())).thenReturn(new PaymentGateway.ProviderError(
                403, "REJECT_CARD_PAYMENT", "한도초과 혹은 잔액부족으로 결제에 실패했습니다."));

            mockMvc.perform(confirm(buyer, order.getId()))
                .andExpect(failure(HttpStatus.UNPROCESSABLE_ENTITY, "PAYMENT_FAILED"));

            verify(paymentGateway).approve(any());
        }

        @DisplayName("재조회 후에도 PG 결과가 UNKNOWN이면 503 PAYMENT_CANCELLATION_PENDING을 반환한다.")
        @Test
        void returnsCancellationPendingWhenProviderOutcomeRemainsUnknown() throws Exception {
            User buyer = fixture.user();
            Product product = fixture.product(fixture.brand("PG-" + System.nanoTime()), "상품", 2_000L, 5);
            var order = fixture.draftOrder(buyer, fixture.item(product, 2));
            when(paymentGateway.approve(any())).thenReturn(new PaymentGateway.Unknown());

            mockMvc.perform(confirm(buyer, order.getId()))
                .andExpect(failure(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_CANCELLATION_PENDING"))
                .andExpect(jsonPath("$.meta.message")
                    .value("결제 결과 확인 중 문제가 발생했습니다. 결제 취소가 진행될 예정입니다."));

            verify(paymentGateway).approve(any());
        }
    }

    private MockHttpServletRequestBuilder confirm(User buyer, Long orderId) {
        return post(CONFIRM, orderId)
            .with(customer(buyer))
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "paymentKey": "test-payment-%s",
                  "paymentAttemptKey": "attempt-%s"
                }
                """.formatted(orderId, orderId));
    }
}
