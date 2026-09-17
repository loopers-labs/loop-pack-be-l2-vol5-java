package com.loopers.interfaces.api.admin.order;

import com.loopers.domain.common.Money;
import com.loopers.domain.order.OrderLines;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.product.ProductSnapshot;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
class OrderAdminV1ApiE2ETest {

    private static final String ENDPOINT = "/api-admin/v1/orders";
    private static final RequestPostProcessor ADMIN = user("admin").roles("ADMIN");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderJpaRepository orderJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private OrderModel order(Long userId, boolean confirmed) {
        OrderModel order = OrderModel.create(
            userId,
            OrderLines.of(List.of(new OrderLines.Line(1L, 2))),
            Map.of(1L, new ProductSnapshot(1L, "에어맥스", Money.of(3_500)))
        );
        if (confirmed) {
            order.confirm(ZonedDateTime.now());
        }
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

        @DisplayName("ORD-06 확정된 주문의 품목·상태·금액·결제 결과와 구매자를 돌려준다.")
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

        @DisplayName("없는 주문은 404다.")
        @Test
        void returnsNotFound() throws Exception {
            // act & assert
            mockMvc.perform(get(ENDPOINT + "/999").with(ADMIN))
                .andExpect(status().isNotFound());
        }
    }
}
