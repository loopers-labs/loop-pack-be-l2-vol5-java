package com.loopers.interfaces.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.OrderEventPublisher;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OrderItemCommand;
import com.loopers.application.payment.PgPaymentPort;
import com.loopers.application.payment.PgPaymentResult;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentAttemptService;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PaymentCallbackTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired OrderFacade orderFacade;
    @Autowired PaymentAttemptService paymentAttemptService;
    @Autowired UserJpaRepository userJpaRepository;
    @Autowired BrandJpaRepository brandJpaRepository;
    @Autowired ProductJpaRepository productJpaRepository;
    @Autowired OrderJpaRepository orderJpaRepository;
    @Autowired DatabaseCleanUp databaseCleanUp;

    @MockBean PgPaymentPort pgPaymentPort;
    @MockBean OrderEventPublisher orderEventPublisher;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Long createPendingOrder() {
        UserModel user = userJpaRepository.save(new UserModel("콜백 사용자"));
        BrandModel brand = brandJpaRepository.save(new BrandModel("브랜드"));
        ProductModel product = productJpaRepository.save(new ProductModel(brand.getId(), "상품", 10_000L, 5));

        when(pgPaymentPort.pay(any())).thenReturn(
            new PgPaymentResult(null, "LP-ORD-0000000001", 10_000L, PgPaymentResult.PgStatus.UNKNOWN)
        );

        Long orderId = orderFacade.createOrder(user.getId(), List.of(new OrderItemCommand(product.getId(), 1))).id();
        orderFacade.confirmOrder(user.getId(), orderId, UUID.randomUUID().toString(), "http://localhost/cb");
        return orderId;
    }

    @DisplayName("SUCCESS 콜백 수신 후 PG 검증이 성공하면 주문이 CONFIRMED가 된다.")
    @Test
    void callback_withSuccessVerification_confirmsOrder() throws Exception {
        Long orderId = createPendingOrder();
        String txKey = "TX-CB-001";

        when(pgPaymentPort.getByTransactionKey(anyString())).thenReturn(
            new PgPaymentResult(txKey, "LP-ORD-" + String.format("%010d", orderId), 10_000L, PgPaymentResult.PgStatus.SUCCESS)
        );

        var body = new com.loopers.interfaces.api.pg.PaymentCallbackV1Controller.CallbackRequest(
            "LP-ORD-" + String.format("%010d", orderId), txKey, "SUCCESS"
        );

        mvc.perform(post("/api/v1/orders/" + orderId + "/payment/callback")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk());

        OrderModel order = orderJpaRepository.findById(orderId).get();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);

        var attempt = paymentAttemptService.getByOrderId(orderId);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS);
    }

    @DisplayName("이미 종결된 주문에 콜백이 오면 멱등하게 200을 반환한다.")
    @Test
    void callback_onTerminalAttempt_returnsOkIdempotently() throws Exception {
        UserModel user = userJpaRepository.save(new UserModel("성공 사용자"));
        BrandModel brand = brandJpaRepository.save(new BrandModel("브랜드2"));
        ProductModel product = productJpaRepository.save(new ProductModel(brand.getId(), "상품", 10_000L, 5));

        when(pgPaymentPort.pay(any())).thenReturn(
            new PgPaymentResult("TX-DONE", "LP-ORD-0000000001", 10_000L, PgPaymentResult.PgStatus.SUCCESS)
        );

        Long orderId = orderFacade.createOrder(user.getId(), List.of(new OrderItemCommand(product.getId(), 1))).id();
        orderFacade.confirmOrder(user.getId(), orderId, UUID.randomUUID().toString(), "http://localhost/cb");

        var body = new com.loopers.interfaces.api.pg.PaymentCallbackV1Controller.CallbackRequest(
            "LP-ORD-" + String.format("%010d", orderId), "TX-DONE", "SUCCESS"
        );

        mvc.perform(post("/api/v1/orders/" + orderId + "/payment/callback")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk());
    }

    @DisplayName("수동 대사 요청 시 PG에서 SUCCESS를 받으면 주문이 CONFIRMED가 된다.")
    @Test
    void reconcile_confirmsOrder_whenPgReturnsSuccess() throws Exception {
        Long orderId = createPendingOrder();

        when(pgPaymentPort.getByProviderOrderId(anyString())).thenReturn(List.of(
            new PgPaymentResult("TX-REC-001", "LP-ORD-" + String.format("%010d", orderId), 10_000L, PgPaymentResult.PgStatus.SUCCESS)
        ));

        mvc.perform(post("/api/v1/orders/" + orderId + "/payment/reconcile"))
            .andExpect(status().isOk());

        OrderModel order = orderJpaRepository.findById(orderId).get();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }
}
