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
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.UUID;

import static com.loopers.support.http.ApiHttp.customer;
import static com.loopers.support.http.ApiHttp.failure;
import static com.loopers.support.http.ApiHttp.success;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlTestContainersConfig.class)
@DisplayName("[R-ACCESS-03, C-13] 본인 주문의 기존 PG 결제 결과를 재조정한다")
class PaymentReconcileHttpTest {

    private static final String RECONCILE = "/api/v1/orders/{orderId}/payment/reconcile";
    private static final OffsetDateTime APPROVED_AT = OffsetDateTime.parse("2026-10-21T15:00:00+09:00");

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

    @DisplayName("[상태 전이] 같은 주문의 미확정 결제가 권위 조회에서 SUCCESS면 한 번 확정하고 예약을 소비한다")
    @Test
    void appliesMatchingSuccessFromExistingPaymentLookup() throws Exception {
        Scenario scenario = unresolvedScenario();
        when(paymentGateway.findByPaymentKey(scenario.paymentKey())).thenReturn(approved(scenario));

        mockMvc.perform(reconcile(scenario.buyer(), scenario.orderId()))
            .andExpect(success(HttpStatus.OK))
            .andExpect(jsonPath("$.data.orderStatus").value("CONFIRMED"))
            .andExpect(jsonPath("$.data.paymentStatus").value("SUCCESS"));

        Order reconciled = readOrder(scenario.orderId());
        assertThat(reconciled.getPayment().status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(reconciled.getStockReservationStatus().name()).isEqualTo("CONSUMED");
        verify(paymentGateway).findByPaymentKey(scenario.paymentKey());
        verify(paymentGateway, never()).approve(any());
        verify(paymentGateway, never()).cancel(any());
    }

    @DisplayName("[오류 추측] 권위 조회가 UNKNOWN이면 202와 UNKNOWN 및 RESERVED를 보존한다")
    @Test
    void preservesUnknownPaymentAndReservationWhenLookupIsUnresolved() throws Exception {
        Scenario scenario = unresolvedScenario();
        when(paymentGateway.findByPaymentKey(scenario.paymentKey())).thenReturn(new PaymentGateway.Unknown());

        mockMvc.perform(reconcile(scenario.buyer(), scenario.orderId()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted())
            .andExpect(jsonPath("$.meta.result").value("PENDING"))
            .andExpect(jsonPath("$.data.orderStatus").value("DRAFT"))
            .andExpect(jsonPath("$.data.paymentStatus").value("UNKNOWN"))
            .andExpect(jsonPath("$.data.statusUrl").value("/api/v1/orders/" + scenario.orderId()));

        Order unresolved = readOrder(scenario.orderId());
        assertThat(unresolved.getPayment().status()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(unresolved.getStockReservationStatus().name()).isEqualTo("RESERVED");
        verify(paymentGateway, never()).approve(any());
        verify(paymentGateway, never()).cancel(any());
    }

    @DisplayName("[오류 추측] 다른 고객의 주문은 404로 숨기고 PG 조회를 호출하지 않는다")
    @Test
    void doesNotReconcileAnotherCustomersOrder() throws Exception {
        Scenario scenario = unresolvedScenario();
        User otherCustomer = fixture.user();

        mockMvc.perform(reconcile(otherCustomer, scenario.orderId()))
            .andExpect(failure(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND"));

        verify(paymentGateway, never()).findByPaymentKey(any());
        verify(paymentGateway, never()).approve(any());
    }

    @DisplayName("[오류 추측] PG 거래 금액이 주문 금액과 다르면 UNKNOWN 및 RESERVED를 보존한다")
    @Test
    void preservesUnknownWhenProviderAmountDoesNotMatchOrder() throws Exception {
        Scenario scenario = unresolvedScenario();
        when(paymentGateway.findByPaymentKey(scenario.paymentKey())).thenReturn(new PaymentGateway.Approved(
            scenario.paymentKey(), scenario.providerOrderId(), scenario.amount() + 1, APPROVED_AT));

        mockMvc.perform(reconcile(scenario.buyer(), scenario.orderId()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted())
            .andExpect(jsonPath("$.data.paymentStatus").value("UNKNOWN"));

        Order unresolved = readOrder(scenario.orderId());
        assertThat(unresolved.getStatus().name()).isEqualTo("DRAFT");
        assertThat(unresolved.getPayment().status()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(unresolved.getStockReservationStatus().name()).isEqualTo("RESERVED");
        verify(paymentGateway, never()).approve(any());
    }

    @DisplayName("[상태 전이] 성공 결과를 다시 요청하면 저장된 결과를 반환하고 PG 조회나 create를 반복하지 않는다")
    @Test
    void returnsStoredSuccessWhenReconcileIsRepeated() throws Exception {
        Scenario scenario = unresolvedScenario();
        when(paymentGateway.findByPaymentKey(scenario.paymentKey())).thenReturn(approved(scenario));

        mockMvc.perform(reconcile(scenario.buyer(), scenario.orderId())).andExpect(success(HttpStatus.OK));
        mockMvc.perform(reconcile(scenario.buyer(), scenario.orderId())).andExpect(success(HttpStatus.OK));

        verify(paymentGateway, times(1)).findByPaymentKey(scenario.paymentKey());
        verify(paymentGateway, never()).approve(any());
        assertThat(readOrder(scenario.orderId()).getPayment().status()).isEqualTo(PaymentStatus.SUCCESS);
    }

    @DisplayName("[동등 클래스 분할] 결제 시도가 없는 주문은 INVALID_REQUEST로 거절한다")
    @Test
    void rejectsOrderWithoutPaymentAttempt() throws Exception {
        User buyer = fixture.user();
        Product product = fixture.product(fixture.brand("reconcile-" + UUID.randomUUID()), "상품", 2_000L, 3);
        Order order = fixture.draftOrder(buyer, fixture.item(product, 1));

        mockMvc.perform(reconcile(buyer, order.getId()))
            .andExpect(failure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST"));

        assertThat(readOrder(order.getId()).getPayment()).isNull();
        verify(paymentGateway, never()).findByPaymentKey(any());
        verify(paymentGateway, never()).approve(any());
    }

    private Scenario unresolvedScenario() {
        User buyer = fixture.user();
        Product product = fixture.product(fixture.brand("reconcile-" + UUID.randomUUID()), "상품", 2_000L, 3);
        Order order = fixture.draftOrder(buyer, fixture.item(product, 2));
        String paymentKey = "reconcile-payment-" + UUID.randomUUID();
        String attemptKey = "reconcile-attempt-" + UUID.randomUUID();
        String providerOrderId = "LP-ORD-%010d".formatted(order.getId());

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.executeWithoutResult(status -> {
            var plan = paymentTransactionService.prepare(buyer.getId(), order.getId(), paymentKey, attemptKey);
            plan.order().getPayment().recordResult(new PaymentGateway.Unknown());
            entityManager.flush();
        });
        return new Scenario(buyer, order.getId(), product.getId(), paymentKey, attemptKey, providerOrderId,
            order.getTotalAmount());
    }

    private PaymentGateway.Approved approved(Scenario scenario) {
        return new PaymentGateway.Approved(
            scenario.paymentKey(), scenario.providerOrderId(), scenario.amount(), APPROVED_AT);
    }

    private Order readOrder(Long orderId) {
        entityManager.clear();
        return entityManager.find(Order.class, orderId);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder reconcile(
        User requester,
        Long orderId
    ) {
        return post(RECONCILE, orderId).with(customer(requester)).with(csrf());
    }

    private record Scenario(
        User buyer,
        Long orderId,
        Long productId,
        String paymentKey,
        String attemptKey,
        String providerOrderId,
        long amount
    ) {
    }
}
