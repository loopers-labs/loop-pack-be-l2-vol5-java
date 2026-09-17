package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderConfirmFacade;
import com.loopers.domain.order.OrderItemCommand;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.point.PointService;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.fixture.ProductFixture;
import com.loopers.fixture.UserFixture;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("관리자 주문 API 는 소유자와 무관하게 주문을 조회한다.")
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class OrderAdminV1ApiTest {

    private static final String ENDPOINT = "/api-admin/v1/orders";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserFixture userFixture;
    @Autowired
    private ProductFixture productFixture;
    @Autowired
    private OrderService orderService;
    @Autowired
    private OrderConfirmFacade orderConfirmFacade;
    @Autowired
    private PointService pointService;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("GET /api-admin/v1/orders")
    @Nested
    class GetOrders {
        @DisplayName("구매자를 포함해 모든 사용자의 주문을 최신순으로 반환한다.")
        @Test
        void returnsEveryOrder() throws Exception {
            UserModel first = userFixture.createUserWithPoint();
            UserModel second = userFixture.createUserWithPoint();
            ProductModel shoes = productFixture.createProduct("운동화", 10_000L, 100L);
            orderService.create(first.getId(), List.of(new OrderItemCommand(shoes.getId(), 1L)));
            OrderModel latest = orderService.create(second.getId(), List.of(new OrderItemCommand(shoes.getId(), 2L)));

            mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.items[0].id").value(latest.getId()))
                .andExpect(jsonPath("$.data.items[0].userId").value(second.getId()))
                .andExpect(jsonPath("$.data.items[1].userId").value(first.getId()));
        }

        @DisplayName("주문마다 그 주문의 모든 품목과 금액·상태를 포함한다.")
        @Test
        void includesItemsAndAmounts() throws Exception {
            UserModel user = userFixture.createUserWithPoint();
            ProductModel shoes = productFixture.createProduct("운동화", 10_000L, 100L);
            ProductModel cap = productFixture.createProduct("모자", 5_000L, 100L);
            orderService.create(user.getId(), List.of(
                new OrderItemCommand(shoes.getId(), 1L),
                new OrderItemCommand(cap.getId(), 2L)
            ));

            mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.data.items[0].orderTotal").value(20_000))
                .andExpect(jsonPath("$.data.items[0].items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].usedPointAmount").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].paymentAmount").doesNotExist());
        }

        @DisplayName("[잠정] page·size·sort=oldest 로 끊어 반환한다.")
        @Test
        void returnsRequestedPage() throws Exception {
            UserModel user = userFixture.createUserWithPoint();
            ProductModel shoes = productFixture.createProduct("운동화", 10_000L, 100L);
            OrderModel first = orderService.create(user.getId(), List.of(new OrderItemCommand(shoes.getId(), 1L)));
            orderService.create(user.getId(), List.of(new OrderItemCommand(shoes.getId(), 2L)));
            orderService.create(user.getId(), List.of(new OrderItemCommand(shoes.getId(), 3L)));

            mockMvc.perform(get(ENDPOINT).param("page", "0").param("size", "2").param("sort", "oldest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.items[0].id").value(first.getId()));
        }

        @DisplayName("지원하지 않는 sort 는 400 INVALID_SORT 로 거절한다.")
        @Test
        void rejectsUnsupportedSort() throws Exception {
            mockMvc.perform(get(ENDPOINT).param("sort", "likes_desc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("INVALID_SORT"));
        }
    }

    @DisplayName("GET /api-admin/v1/orders/{orderId}")
    @Nested
    class GetOrder {
        @DisplayName("확정된 주문의 구매자·품목·포인트 사용액·결제액을 반환한다.")
        @Test
        void returnsConfirmedOrder() throws Exception {
            UserModel user = userFixture.createUserWithPoint();
            pointService.charge(user.getId(), 50_000L);
            ProductModel shoes = productFixture.createProduct("운동화", 10_000L, 100L);
            OrderModel created = orderService.create(user.getId(), List.of(new OrderItemCommand(shoes.getId(), 2L)));
            orderConfirmFacade.confirm(user.getId(), created.getId());

            mockMvc.perform(get(ENDPOINT + "/" + created.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(created.getId()))
                .andExpect(jsonPath("$.data.userId").value(user.getId()))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.orderTotal").value(20_000))
                .andExpect(jsonPath("$.data.usedPointAmount").value(20_000))
                .andExpect(jsonPath("$.data.paymentAmount").value(20_000))
                .andExpect(jsonPath("$.data.items[0].productId").value(shoes.getId()))
                .andExpect(jsonPath("$.data.items[0].quantity").value(2))
                .andExpect(jsonPath("$.data.items[0].unitPrice").value(10_000))
                .andExpect(jsonPath("$.data.items[0].amount").value(20_000));
        }

        @DisplayName("존재하지 않는 주문은 404 ORDER_NOT_FOUND 로 응답한다.")
        @Test
        void rejectsUnknownOrder() throws Exception {
            mockMvc.perform(get(ENDPOINT + "/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("ORDER_NOT_FOUND"));
        }
    }
}
