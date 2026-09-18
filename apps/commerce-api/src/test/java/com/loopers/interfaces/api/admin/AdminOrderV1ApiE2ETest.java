package com.loopers.interfaces.api.admin;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
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

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminOrderV1ApiE2ETest {

    private static final String ENDPOINT_ORDERS = "/api-admin/v1/orders";

    private final MockMvc mockMvc;
    private final OrderJpaRepository orderJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    public AdminOrderV1ApiE2ETest(
        MockMvc mockMvc,
        OrderJpaRepository orderJpaRepository,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.mockMvc = mockMvc;
        this.orderJpaRepository = orderJpaRepository;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("GET /api-admin/v1/orders")
    @Nested
    class GetOrders {
        @DisplayName("관리자가 요청하면, 구매자와 무관하게 전체 주문을 반환한다.")
        @Test
        void returnsAllOrders_regardlessOfOrderer() throws Exception {
            // arrange
            saveOrder(1L, 10L, 2, 1000L);
            saveOrder(2L, 20L, 1, 3000L);

            // act & assert
            mockMvc.perform(get(ENDPOINT_ORDERS).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
        }

        @DisplayName("구매자로 필터하면, 해당 구매자의 주문만 반환한다.")
        @Test
        void filtersByUserId() throws Exception {
            // arrange
            saveOrder(1L, 10L, 2, 1000L);
            saveOrder(2L, 20L, 1, 3000L);

            // act & assert
            mockMvc.perform(get(ENDPOINT_ORDERS + "?userId=1").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].userId").value(1));
        }

        @DisplayName("나중에 생성된 주문이 먼저 반환된다.")
        @Test
        void sortsByLatest() throws Exception {
            // arrange
            Long firstId = saveOrder(1L, 10L, 1, 1000L);
            Long secondId = saveOrder(1L, 20L, 1, 2000L);

            // act & assert
            mockMvc.perform(get(ENDPOINT_ORDERS).with(user("admin").roles("ADMIN")))
                .andExpect(jsonPath("$.data[0].orderId").value(secondId))
                .andExpect(jsonPath("$.data[1].orderId").value(firstId));
        }

        @DisplayName("일반 사용자가 요청하면, 403 응답을 받는다.")
        @Test
        void returnsForbidden_whenRequesterIsNotAdmin() throws Exception {
            // act & assert
            mockMvc.perform(get(ENDPOINT_ORDERS).with(user("user").roles("USER")))
                .andExpect(status().isForbidden());
        }
    }

    @DisplayName("GET /api-admin/v1/orders/{orderId}")
    @Nested
    class GetOrder {
        @DisplayName("남의 주문이어도, 관리자는 상세를 조회할 수 있다.")
        @Test
        void returnsOrderDetail_regardlessOfOwner() throws Exception {
            // arrange
            Long orderId = saveOrder(1L, 10L, 2, 1000L);

            // act & assert - 고객 API 와 달리 소유권을 검증하지 않는다
            mockMvc.perform(get(ENDPOINT_ORDERS + "/" + orderId).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(1))
                .andExpect(jsonPath("$.data.totalAmount").value(2000))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
        }

        @DisplayName("존재하지 않는 주문이면, 404 응답을 받는다.")
        @Test
        void returnsNotFound_whenOrderIsAbsent() throws Exception {
            // act & assert
            mockMvc.perform(get(ENDPOINT_ORDERS + "/-1").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
        }

        @DisplayName("일반 사용자가 요청하면, 403 응답을 받는다.")
        @Test
        void returnsForbidden_whenRequesterIsNotAdmin() throws Exception {
            // arrange
            Long orderId = saveOrder(1L, 10L, 2, 1000L);

            // act & assert
            mockMvc.perform(get(ENDPOINT_ORDERS + "/" + orderId).with(user("user").roles("USER")))
                .andExpect(status().isForbidden());
        }
    }

    private Long saveOrder(Long userId, Long productId, int quantity, long unitPrice) {
        Order order = new Order(userId, List.of(new OrderItem(productId, quantity, unitPrice)));
        return orderJpaRepository.save(order).getId();
    }
}
