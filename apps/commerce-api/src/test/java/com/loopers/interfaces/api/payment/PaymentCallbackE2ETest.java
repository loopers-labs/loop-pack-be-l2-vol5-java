package com.loopers.interfaces.api.payment;

import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OrderPaymentTransactions;
import com.loopers.application.user.UserRegistrationService;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.ReservationStatus;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentEvent;
import com.loopers.domain.payment.PaymentProvider;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.payment.PaymentEventJpaRepository;
import com.loopers.infrastructure.payment.ResilientPaymentProvider;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.support.payment.FakePaymentServer;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** PG callback을 실제 controller로 받아, 권위 조회·TX B 저장·TX C 반영과 저장 결과의 복구를 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentCallbackE2ETest {

    private static final FakePaymentServer PG = startPg();

    @DynamicPropertySource
    static void pgUrl(DynamicPropertyRegistry registry) {
        registry.add("commerce.payment.pg.base-url", PG::baseUrl);
    }

    @Autowired private MockMvc mvc;
    @Autowired private UserRegistrationService registration;
    @Autowired private OrderFacade orderFacade;
    @Autowired private OrderPaymentTransactions paymentTransactions;
    @Autowired private PaymentProvider paymentProvider;
    @Autowired private ResilientPaymentProvider resilientPaymentProvider;
    @Autowired private BrandJpaRepository brandJpaRepository;
    @Autowired private ProductJpaRepository productJpaRepository;
    @Autowired private OrderJpaRepository orderJpaRepository;
    @Autowired private PaymentEventJpaRepository paymentEventJpaRepository;
    @Autowired private DatabaseCleanUp databaseCleanUp;

    private Long userId;
    private Long productId;
    private Long orderId;

    @BeforeEach
    void setUp() {
        PG.reset();
        resilientPaymentProvider.lookupCircuitBreaker().reset();
        userId = registration.register().getId();
        Long brandId = brandJpaRepository.save(new BrandModel("나이키", null)).getId();
        productId = productJpaRepository.save(new ProductModel(brandId, "양말", 1_000L, 5)).getId();
        orderId = orderFacade.create(userId, List.of(new OrderFacade.OrderLine(productId, 3))).id();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @AfterAll
    static void stopPg() throws InterruptedException {
        PG.close();
    }

    @Test
    void callbackTriggersAuthoritativeLookupAndAppliesSuccessOnce() throws Exception {
        confirm();
        PG.changeStatus("TEST-TX-1", "SUCCESS");

        callback(providerOrderId(), "TEST-TX-1", "SUCCESS").andExpect(status().isOk());

        assertOrder(OrderStatus.CONFIRMED, ReservationStatus.CONSUMED);
        assertThat(stock()).isEqualTo(2);
        assertThat(PG.requests().getLast().path()).isEqualTo("/api/v1/payments/TEST-TX-1");
        PaymentEvent last = events().getLast();
        assertThat(last.getSource()).isEqualTo(PaymentEvent.Source.CALLBACK);
        assertThat(last.getResult()).isEqualTo(PaymentAttempt.Result.APPLIED);
    }

    @Test
    void forgedSuccessBodyDoesNotConfirmWhilePgSaysPending() throws Exception {
        confirm();

        callback(providerOrderId(), "TEST-TX-1", "SUCCESS").andExpect(status().isOk());

        assertOrder(OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED);
        assertThat(stock()).isEqualTo(2);
        assertThat(PG.lookupCalls()).isEqualTo(1);
    }

    @Test
    void callbackForUnknownOrderIsAcknowledgedWithoutLookup() throws Exception {
        confirm();

        callback("LP-ORD-9999999999", "TEST-TX-9", "SUCCESS").andExpect(status().isOk());

        assertThat(PG.lookupCalls()).isZero();
        assertOrder(OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED);
    }

    @Test
    void callbackAfterReconcileFinishedIsAcknowledgedWithoutLookup() throws Exception {
        confirm();
        PG.changeStatus("TEST-TX-1", "SUCCESS");
        orderFacade.reconcile(userId, orderId);
        int lookupsBefore = PG.lookupCalls();

        callback(providerOrderId(), "TEST-TX-1", "SUCCESS").andExpect(status().isOk());

        assertThat(PG.lookupCalls()).isEqualTo(lookupsBefore);
        assertOrder(OrderStatus.CONFIRMED, ReservationStatus.CONSUMED);
        assertThat(stock()).isEqualTo(2);
    }

    @Test
    void callbackFirstThenReconcileConvergeWithoutAnotherChange() throws Exception {
        confirm();
        PG.changeStatus("TEST-TX-1", "FAILED");

        callback(providerOrderId(), "TEST-TX-1", "FAILED").andExpect(status().isOk());
        callback(providerOrderId(), "TEST-TX-1", "FAILED").andExpect(status().isOk());
        orderFacade.reconcile(userId, orderId);

        assertOrder(OrderStatus.PAYMENT_FAILED, ReservationStatus.RELEASED);
        assertThat(stock()).isEqualTo(5);
        assertThat(PG.lookupCalls()).isEqualTo(1);
        assertThat(PG.createCalls()).isEqualTo(1);
    }

    @Test
    void callbackForAttemptWithoutKeyLooksUpByProviderOrderId() throws Exception {
        PG.dropNextCreateResponse();
        confirm();
        PG.changeStatus("TEST-TX-1", "SUCCESS");

        callback(providerOrderId(), "TEST-TX-1", "SUCCESS").andExpect(status().isOk());

        assertThat(PG.requests().getLast().rawQuery()).isEqualTo("orderId=" + providerOrderId());
        assertOrder(OrderStatus.CONFIRMED, ReservationStatus.CONSUMED);
    }

    @Test
    void failedLookupAnswersServiceUnavailableAndKeepsStoredState() throws Exception {
        confirm();
        PG.changeStatus("TEST-TX-1", "SUCCESS");
        int eventsBefore = events().size();
        for (int i = 0; i < 3; i++) {
            PG.replyNext(503, "");
        }

        callback(providerOrderId(), "TEST-TX-1", "SUCCESS").andExpect(status().isServiceUnavailable());

        assertOrder(OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED);
        assertThat(events()).hasSize(eventsBefore);
        assertThat(PG.lookupCalls()).isEqualTo(3);

        callback(providerOrderId(), "TEST-TX-1", "SUCCESS").andExpect(status().isOk());
        assertOrder(OrderStatus.CONFIRMED, ReservationStatus.CONSUMED);
    }

    @Test
    void openCircuitAnswersServiceUnavailableWithoutLookup() throws Exception {
        confirm();
        resilientPaymentProvider.lookupCircuitBreaker().transitionToOpenState();

        callback(providerOrderId(), "TEST-TX-1", "SUCCESS").andExpect(status().isServiceUnavailable());

        assertThat(PG.lookupCalls()).isZero();
        assertOrder(OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED);
    }

    @Test
    void blankProviderOrderIdIsRejected() throws Exception {
        callback(" ", "TEST-TX-1", "SUCCESS").andExpect(status().isBadRequest());

        assertThat(PG.lookupCalls()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"SUCCESS, CONFIRMED, CONSUMED, 2", "FAILED, PAYMENT_FAILED, RELEASED, 5"})
    void storedFinalResultIsAppliedWithoutLookupEvenWhenCircuitIsOpen(
        String result, OrderStatus orderStatus, ReservationStatus reservation, int stockAfter
    ) throws Exception {
        confirm();
        PG.changeStatus("TEST-TX-1", result);
        paymentTransactions.applyLookup(orderId, paymentProvider.findByTransactionKey("TEST-TX-1"),
            PaymentEvent.Source.RECONCILE);
        assertOrder(OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED);
        int eventsBefore = events().size();
        resilientPaymentProvider.lookupCircuitBreaker().transitionToOpenState();

        callback(providerOrderId(), "forged-key", "PENDING").andExpect(status().isOk());
        callback(providerOrderId(), "forged-key", "PENDING").andExpect(status().isOk());

        assertOrder(orderStatus, reservation);
        assertThat(stock()).isEqualTo(stockAfter);
        assertThat(PG.lookupCalls()).isEqualTo(1);
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(events()).hasSize(eventsBefore);
    }

    private void confirm() {
        orderFacade.confirm(userId, orderId,
            new OrderFacade.PaymentCommand("attempt-" + orderId, "TEST", "0000-0000-0000-0000"));
    }

    private ResultActions callback(String providerOrderId, String transactionKey, String status) throws Exception {
        return mvc.perform(post("/api/v1/payments/callback").contentType(MediaType.APPLICATION_JSON)
            .content("{\"providerOrderId\":\"" + providerOrderId + "\",\"transactionKey\":\"" + transactionKey
                + "\",\"status\":\"" + status + "\"}"));
    }

    private String providerOrderId() {
        return "LP-ORD-%010d".formatted(orderId);
    }

    private void assertOrder(OrderStatus status, ReservationStatus reservation) {
        OrderModel order = orderJpaRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(status);
        assertThat(order.getReservationStatus()).isEqualTo(reservation);
    }

    private int stock() {
        return productJpaRepository.findById(productId).orElseThrow().getStockQuantity();
    }

    private List<PaymentEvent> events() {
        return paymentEventJpaRepository.findAll().stream().sorted(Comparator.comparing(PaymentEvent::getId)).toList();
    }

    private static FakePaymentServer startPg() {
        try {
            return new FakePaymentServer();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
