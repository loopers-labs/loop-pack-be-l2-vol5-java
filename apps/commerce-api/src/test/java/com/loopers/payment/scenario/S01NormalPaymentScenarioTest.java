package com.loopers.payment.scenario;

import com.loopers.order.domain.OrderStatus;
import com.loopers.payment.domain.PaymentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S-1 정상 PG 주문 (plan.md 10장, C-1·C-5): 외부 거래 1, 주문 CONFIRMED, 예약 소비 1, 포인트 변화 0.
 */
@DisplayName("S-1 정상 PG 주문")
class S01NormalPaymentScenarioTest extends PaymentScenarioSupport {

    @DisplayName("S-1 결제를 접수(202)하고 PG가 SUCCESS callback을 보내면 외부 거래 1·주문 CONFIRMED(PG, 2,000원)·예약 CONSUMED 1이고, 재고는 5→3에서 더 줄지 않으며 포인트 잔액 10,000은 그대로다.")
    @Test
    void confirmsOnceWithPgAndKeepsPoints() throws Exception {
        // arrange: 포인트를 충전해 둔다 — PG 결제가 포인트를 건드리지 않는지 보기 위해서다
        mockMvc.perform(post("/api/v1/points/charge").header(USER_HEADER, user.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 10000}"))
            .andExpect(status().isOk());
        long pointHistories = pointHistoryCount();
        Long orderId = createOrder(2);

        // act: 확정 요청 → 비동기 발송 → PG 수락
        confirm(orderId, user, "attempt-1")
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.orderStatus").value("PAYMENT_PENDING"));
        awaitPayment(orderId, PaymentStatus.PENDING);
        assertThat(stockOf(airMax)).isEqualTo(3);
        String transactionKey = paymentOf(orderId).getTransactionKey();

        // act: PG가 승인하고 callback을 보낸다
        PG.complete(transactionKey, "SUCCESS", null);
        int callbackStatus = PG.sendCallback(transactionKey);

        // assert
        assertThat(callbackStatus).isEqualTo(200);
        getPayment(orderId)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.paymentStatus").value("SUCCESS"))
            .andExpect(jsonPath("$.data.orderStatus").value("CONFIRMED"))
            .andExpect(jsonPath("$.data.transactionKey").value(transactionKey));
        getOrder(orderId, user)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.data.paymentMethod").value("PG"))
            .andExpect(jsonPath("$.data.paidAmount").value(2_000));
        assertThat(PG.transactionsCreated()).isEqualTo(1);
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(orderStatusOf(orderId)).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(reservationsOf(orderId)).containsExactly("CONSUMED");
        assertThat(stockOf(airMax)).isEqualTo(3);
        mockMvc.perform(get("/api/v1/points").header(USER_HEADER, user.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.balance").value(10_000));
        assertThat(pointHistoryCount()).isEqualTo(pointHistories);
    }

    private long pointHistoryCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM point_histories", Long.class);
    }
}
