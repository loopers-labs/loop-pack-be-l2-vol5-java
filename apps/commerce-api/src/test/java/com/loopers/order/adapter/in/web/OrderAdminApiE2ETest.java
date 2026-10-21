package com.loopers.order.adapter.in.web;

import com.loopers.common.domain.Money;
import com.loopers.order.adapter.out.persistence.OrderJpaRepository;
import com.loopers.order.domain.OrderLines;
import com.loopers.order.domain.OrderModel;
import com.loopers.product.domain.ProductSnapshot;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OrderAdminApiE2ETest {

    private static final String ENDPOINT = "/api-admin/v1/orders";
    private static final RequestPostProcessor ADMIN = user("admin").roles("ADMIN");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderJpaRepository orderJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private static OrderModel draft(Long userId) {
        return OrderModel.create(
            userId,
            OrderLines.of(List.of(new OrderLines.Line(1L, 2))),
            Map.of(1L, new ProductSnapshot(1L, "에어맥스", Money.of(3_500)))
        );
    }

    /**
     * confirmed면 W2~W4에 포인트로 확정한 주문(C-2)을 만든다. W5부터 포인트 확정 경로가 없으므로 저장한 DRAFT 행을 SQL로 바꾼다.
     */
    private OrderModel order(Long userId, boolean confirmed) {
        OrderModel saved = orderJpaRepository.save(draft(userId));
        if (confirmed) {
            jdbcTemplate.update(
                "UPDATE orders SET status = 'CONFIRMED', payment_method = 'POINT', paid_amount = total_amount, confirmed_at = NOW() WHERE id = ?",
                saved.getId());
        }
        return saved;
    }

    /** W5 PG 결제로 확정한 주문: 결제 시작 → 성공 확인 (W5 plan.md 3-1). */
    private OrderModel pgConfirmedOrder(Long userId) {
        OrderModel order = draft(userId);
        order.startPayment();
        order.confirmPaid(ZonedDateTime.now());
        return orderJpaRepository.save(order);
    }

    @DisplayName("GET /api-admin/v1/orders")
    @Nested
    class GetList {

        @DisplayName("ORD-06 모든 구매자의 주문을 구매자 userId와 함께 최신순으로 돌려주고, userId로 거를 수 있다.")
        @Test
        void returnsAllBuyersOrders() throws Exception {
            // arrange
            OrderModel first = order(1L, false);
            OrderModel second = order(2L, true);

            // act & assert
            mockMvc.perform(get(ENDPOINT).with(ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[0].id").value(second.getId()))
                .andExpect(jsonPath("$.data.content[0].userId").value(2))
                .andExpect(jsonPath("$.data.content[0].paidAmount").value(7_000))
                .andExpect(jsonPath("$.data.content[1].id").value(first.getId()));
            mockMvc.perform(get(ENDPOINT).param("userId", "1").with(ADMIN))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(first.getId()));
        }

        @DisplayName("ROLE_USER나 식별 없는 요청은 403이다.")
        @Test
        void forbidsNonAdmin() throws Exception {
            // act & assert
            mockMvc.perform(get(ENDPOINT).with(user("customer").roles("USER")))
                .andExpect(status().isForbidden());
            mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isForbidden());
        }
    }

    @DisplayName("GET /api-admin/v1/orders/{orderId}")
    @Nested
    class GetDetail {

        // W5: 여기의 확정 주문은 이전 POINT 확정 주문이다 (C-2 관리자 조회에서도 POINT·결제액 보존).
        @DisplayName("ORD-06·C-2 확정된 주문의 품목·상태·금액·결제 결과와 구매자를 돌려준다.")
        @Test
        void returnsOrderDetailWithPaymentResult() throws Exception {
            // arrange
            OrderModel confirmed = order(3L, true);

            // act & assert
            mockMvc.perform(get(ENDPOINT + "/" + confirmed.getId()).with(ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(3))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.items[0].quantity").value(2))
                .andExpect(jsonPath("$.data.totalAmount").value(7_000))
                .andExpect(jsonPath("$.data.paidAmount").value(7_000))
                .andExpect(jsonPath("$.data.paymentMethod").value("POINT"))
                .andExpect(jsonPath("$.data.confirmedAt").exists());
        }

        @DisplayName("ORD-06 PG 결제로 확정된 주문은 결제 수단 PG·결제액·확정 시각을 돌려준다.")
        @Test
        void returnsPgConfirmedOrderDetail() throws Exception {
            // arrange
            OrderModel confirmed = pgConfirmedOrder(4L);

            // act & assert
            mockMvc.perform(get(ENDPOINT + "/" + confirmed.getId()).with(ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.paidAmount").value(7_000))
                .andExpect(jsonPath("$.data.paymentMethod").value("PG"))
                .andExpect(jsonPath("$.data.confirmedAt").exists());
        }

        @DisplayName("없는 주문은 404다.")
        @Test
        void returnsNotFound() throws Exception {
            // act & assert
            mockMvc.perform(get(ENDPOINT + "/999").with(ADMIN))
                .andExpect(status().isNotFound());
        }
    }
}
