package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OrderInfo;
import com.loopers.application.order.OrderPaymentInfo;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderV1Controller.class)
class OrderV1ControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderFacade orderFacade;

    @Test
    void createsDraftOrderWithCreatedStatus() throws Exception {
        when(orderFacade.create(1L, List.of(new OrderFacade.OrderLine(10L, 2))))
            .thenReturn(orderInfo("DRAFT", 4_000L, 0L));

        mockMvc.perform(post("/api/v1/orders")
                .header("X-USER-ID", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"productId\":10,\"quantity\":2}]}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.status").value("DRAFT"))
            .andExpect(jsonPath("$.data.totalAmount").value(4_000));

        verify(orderFacade).create(
            eq(1L),
            argThat(lines -> lines.equals(List.of(new OrderFacade.OrderLine(10L, 2))))
        );
    }

    @Test
    void confirmStartsPgPaymentAndReturnsAcceptedWhileResultIsUnknown() throws Exception {
        OrderFacade.PaymentCommand command = new OrderFacade.PaymentCommand("attempt-abc", "TEST", "0000-0000-0000-0000");
        when(orderFacade.confirm(1L, 100L, command)).thenReturn(payment("PAYMENT_PENDING", "RESERVED", "PENDING"));

        mockMvc.perform(post("/api/v1/orders/100/confirm")
                .header("X-USER-ID", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentAttemptKey\":\"attempt-abc\",\"cardType\":\"TEST\",\"cardNo\":\"0000-0000-0000-0000\"}"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.orderStatus").value("PAYMENT_PENDING"))
            .andExpect(jsonPath("$.data.reservationStatus").value("RESERVED"))
            .andExpect(jsonPath("$.data.paymentStatus").value("PENDING"))
            .andExpect(jsonPath("$.data.checked").value(true))
            .andExpect(jsonPath("$.data.statusPath").value("/api/v1/orders/100"))
            .andExpect(jsonPath("$.data.cardNo").doesNotExist())
            .andExpect(jsonPath("$.data.requestFingerprint").doesNotExist());
    }

    @Test
    void changedCardForTheSameAttemptKeyReturnsConflict() throws Exception {
        OrderFacade.PaymentCommand command = new OrderFacade.PaymentCommand("attempt-abc", "TEST", "1111-1111-1111-1111");
        when(orderFacade.confirm(1L, 100L, command))
            .thenThrow(new CoreException(ErrorType.CONFLICT, "같은 결제 요청 키의 요청 내용이 다릅니다."));

        mockMvc.perform(post("/api/v1/orders/100/confirm")
                .header("X-USER-ID", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentAttemptKey\":\"attempt-abc\",\"cardType\":\"TEST\",\"cardNo\":\"1111-1111-1111-1111\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.meta.result").value("FAIL"))
            .andExpect(jsonPath("$.meta.errorCode").value("Conflict"));
    }

    @Test
    void reconcileTellsThatPgCouldNotBeCheckedThisTime() throws Exception {
        when(orderFacade.reconcile(1L, 100L)).thenReturn(payment("PAYMENT_PENDING", "RESERVED", "PENDING").notChecked());

        mockMvc.perform(post("/api/v1/orders/100/payment/reconcile").header("X-USER-ID", "1"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.paymentStatus").value("PENDING"))
            .andExpect(jsonPath("$.data.checked").value(false));
    }

    @Test
    void reconcileReturnsOkWhenPaymentIsFinished() throws Exception {
        when(orderFacade.reconcile(1L, 100L)).thenReturn(payment("CONFIRMED", "CONSUMED", "SUCCESS"));

        mockMvc.perform(post("/api/v1/orders/100/payment/reconcile").header("X-USER-ID", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.orderStatus").value("CONFIRMED"))
            .andExpect(jsonPath("$.data.paymentStatus").value("SUCCESS"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESS", "FAILED"})
    void finalPaymentWithoutOrderApplicationIsNotReportedAsFinished(String paymentStatus) throws Exception {
        when(orderFacade.reconcile(1L, 100L)).thenReturn(payment("PAYMENT_PENDING", "RESERVED", paymentStatus));

        mockMvc.perform(post("/api/v1/orders/100/payment/reconcile").header("X-USER-ID", "1"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.paymentStatus").value(paymentStatus))
            .andExpect(jsonPath("$.data.orderStatus").value("PAYMENT_PENDING"));
    }

    @Test
    void myOrderShowsPaymentStatusButNotPgIdentifiers() throws Exception {
        when(orderFacade.getMyOrder(1L, 100L)).thenReturn(payment("PAYMENT_PENDING", "RESERVED", "UNKNOWN").order());

        mockMvc.perform(get("/api/v1/orders/100").header("X-USER-ID", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PAYMENT_PENDING"))
            .andExpect(jsonPath("$.data.paymentStatus").value("UNKNOWN"))
            .andExpect(jsonPath("$.data.providerOrderId").doesNotExist())
            .andExpect(jsonPath("$.data.transactionKey").doesNotExist());
    }

    @Test
    void returnsOnlyMyOrders() throws Exception {
        when(orderFacade.getMyOrders(1L)).thenReturn(List.of(orderInfo("DRAFT", 4_000L, 0L)));

        mockMvc.perform(get("/api/v1/orders").header("X-USER-ID", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].status").value("DRAFT"));
    }

    private static OrderInfo orderInfo(String status, long totalAmount, long paidAmount) {
        return new OrderInfo(100L, 1L, status, totalAmount, paidAmount, null, null, List.of(), null);
    }

    private static OrderPaymentInfo payment(String orderStatus, String reservationStatus, String paymentStatus) {
        OrderInfo order = new OrderInfo(100L, 1L, orderStatus, 4_000L, 0L, "PG", reservationStatus, List.of(),
            new OrderInfo.PaymentSummary(paymentStatus, "LP-ORD-0000000100", null));
        return new OrderPaymentInfo(order, paymentStatus, "LP-ORD-0000000100", null, 4_000L, true);
    }
}
