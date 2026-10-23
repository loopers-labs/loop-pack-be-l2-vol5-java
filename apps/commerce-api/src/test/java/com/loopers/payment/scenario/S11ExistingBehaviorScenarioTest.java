package com.loopers.payment.scenario;

import com.loopers.order.domain.OrderStatus;
import com.loopers.product.application.port.in.ProductCommandUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S-11 기존 기능 보호 (plan.md 10장, 1장, C-2): POINT 기록·소유권, 재고 부족 시 PG 0회, 삭제 상품.
 * 거절은 모두 TX A 안에서 일어나 전체 rollback되고, commit이 없으니 발송 이벤트도 없다.
 */
@DisplayName("S-11 기존 기능 보호")
class S11ExistingBehaviorScenarioTest extends PaymentScenarioSupport {

    @Autowired
    private ProductCommandUseCase productCommandUseCase;

    private void assertNothingStarted(Long orderId) {
        assertThat(orderStatusOf(orderId)).isEqualTo(OrderStatus.DRAFT);
        assertThat(reservationsOf(orderId)).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payments", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment_outbox", Long.class)).isZero();
        assertThat(PG.createCalls()).isZero();
        assertThat(PG.lookupCalls()).isZero();
    }

    @DisplayName("S-11 주문 뒤 재고가 1로 줄어 부족하면 확정은 409이고, 주문 DRAFT·재고 1·결제 0·PG 호출 0이다.")
    @Test
    void insufficientStockRejectsWithoutPg() throws Exception {
        // arrange
        Long orderId = createOrder(2);
        productCommandUseCase.changeStock(airMax.getId(), 1);

        // act & assert
        confirm(orderId, user, "attempt-1").andExpect(status().isConflict());
        assertNothingStarted(orderId);
        assertThat(stockOf(airMax)).isEqualTo(1);
    }

    @DisplayName("S-11 주문 뒤 상품이 삭제되면 확정은 409이고, 주문 DRAFT·결제 0·PG 호출 0이다.")
    @Test
    void deletedProductRejectsWithoutPg() throws Exception {
        // arrange
        Long orderId = createOrder(2);
        productCommandUseCase.delete(airMax.getId());

        // act & assert
        confirm(orderId, user, "attempt-1").andExpect(status().isConflict());
        assertNothingStarted(orderId);
    }

    @DisplayName("S-11 남의 주문을 확정하면 404이고, 주문 DRAFT·재고 5·결제 0·PG 호출 0이다.")
    @Test
    void othersOrderIsNotFound() throws Exception {
        // arrange
        Long orderId = createOrder(2);

        // act & assert
        confirm(orderId, other, "attempt-1").andExpect(status().isNotFound());
        assertNothingStarted(orderId);
        assertThat(stockOf(airMax)).isEqualTo(5);
    }

    @DisplayName("S-11 이전에 포인트로 확정한 주문은 POINT·2,000원·CONFIRMED 기록 그대로 조회되고, 남에게는 404, 다시 확정하면 409이며 PG를 부르지 않는다.")
    @Test
    void legacyPointOrderIsPreserved() throws Exception {
        // arrange: W2~W4 포인트 확정으로 남은 주문 (포인트 확정 경로는 W5에서 없어져 행을 직접 만든다)
        Long orderId = createOrder(2);
        jdbcTemplate.update(
            "UPDATE orders SET status = 'CONFIRMED', payment_method = 'POINT', paid_amount = 2000, confirmed_at = NOW(6) WHERE id = ?", orderId);

        // act & assert
        getOrder(orderId, user)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.data.paymentMethod").value("POINT"))
            .andExpect(jsonPath("$.data.paidAmount").value(2_000));
        getOrder(orderId, other).andExpect(status().isNotFound());
        confirm(orderId, user, "attempt-1").andExpect(status().isConflict());
        assertThat(orderStatusOf(orderId)).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payments", Long.class)).isZero();
        assertThat(PG.createCalls()).isZero();
    }

    @DisplayName("S-11 포인트 충전·잔액 조회는 그대로다 — 5,000 + 3,000 충전 뒤 잔액 8,000.")
    @Test
    void pointChargeAndBalanceUnaffected() throws Exception {
        // act
        charge(5_000).andExpect(jsonPath("$.data.balance").value(5_000));
        charge(3_000).andExpect(jsonPath("$.data.balance").value(8_000));

        // assert
        mockMvc.perform(get("/api/v1/points").header(USER_HEADER, user.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.balance").value(8_000));
        assertThat(PG.createCalls()).isZero();
    }

    private org.springframework.test.web.servlet.ResultActions charge(long amount) throws Exception {
        return mockMvc.perform(post("/api/v1/points/charge").header(USER_HEADER, user.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": " + amount + "}"))
            .andExpect(status().isOk());
    }
}
