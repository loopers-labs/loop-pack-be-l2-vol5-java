package com.loopers.interfaces.api.admin.order;

import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.dto.OrderItemCommand;
import com.loopers.domain.common.Money;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.Stock;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.support.fixture.ConfirmedOrders;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminOrderApiTest {

    private static final String ORDERS = "/api-admin/v1/orders";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OrderFacade orderFacade;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private RequestPostProcessor admin() {
        return user("admin").roles("ADMIN");
    }

    private User saveUser() {
        User user = User.create();
        user.chargePoint(new Money(10_000));
        return userRepository.save(user);
    }

    private Product saveProduct(String name, long price) {
        return productRepository.save(Product.create(1L, name, new Money(price), new Stock(10)));
    }

    private Long placeOrder(User user, OrderItemCommand... items) {
        return orderFacade.create(user.getId(), List.of(items)).id();
    }

    private ResultActions getAsAdmin(String uri) throws Exception {
        return mvc.perform(get(uri).with(admin()));
    }

    @DisplayName("GET /api-admin/v1/orders")
    @Nested
    class GetList {
        @DisplayName("모든 구매자의 주문을 최신순으로, 구매자 id·품목 수·주문 시각과 함께 반환한다.")
        @Test
        void returnsAllOrders_newestFirst() throws Exception {
            // arrange
            User first = saveUser();
            User second = saveUser();
            Product shirt = saveProduct("티셔츠", 3_000);
            Product socks = saveProduct("양말", 2_000);
            Long firstOrder = placeOrder(first, new OrderItemCommand(shirt.getId(), 1));
            Long secondOrder = placeOrder(
                second, new OrderItemCommand(shirt.getId(), 1), new OrderItemCommand(socks.getId(), 2)
            );
            ConfirmedOrders.confirm(jdbcTemplate, secondOrder);

            // act & assert
            getAsAdmin(ORDERS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[0].id").value(secondOrder))
                .andExpect(jsonPath("$.data.content[0].userId").value(second.getId()))
                .andExpect(jsonPath("$.data.content[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.content[0].totalAmount").value(7_000))
                .andExpect(jsonPath("$.data.content[0].itemCount").value(2))
                .andExpect(jsonPath("$.data.content[0].payment.paidAmount").value(7_000))
                .andExpect(jsonPath("$.data.content[0].orderedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.content[1].id").value(firstOrder))
                .andExpect(jsonPath("$.data.content[1].userId").value(first.getId()))
                // 확정 전(DRAFT)에는 null 필드를 생략하는 JSON 설정(NON_NULL)에 따라 payment 키가 응답에 없다.
                .andExpect(jsonPath("$.data.content[1].payment").doesNotExist());
        }

        @DisplayName("userId 를 주면 그 구매자의 주문만 반환하고, 주문이 없거나 없는 구매자는 오류가 아니라 빈 목록이다.")
        @Test
        void filtersByUserId_andReturnsEmptyList_whenNoOrders() throws Exception {
            // arrange
            User buyer = saveUser();
            User other = saveUser();
            Product shirt = saveProduct("티셔츠", 3_000);
            Long buyersOrder = placeOrder(buyer, new OrderItemCommand(shirt.getId(), 1));
            placeOrder(other, new OrderItemCommand(shirt.getId(), 1));

            // act & assert
            getAsAdmin(ORDERS + "?userId=" + buyer.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(buyersOrder));
            getAsAdmin(ORDERS + "?userId=999999")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(0));
        }

        @DisplayName("size 가 범위를 벗어나거나 userId 가 숫자가 아니면 400 INVALID_INPUT 이다.")
        @Test
        void returnsBadRequest_whenSizeIsOutOfRangeOrUserIdIsNotNumber() throws Exception {
            getAsAdmin(ORDERS + "?size=0")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("INVALID_INPUT"));
            getAsAdmin(ORDERS + "?userId=abc")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("INVALID_INPUT"));
        }
    }

    @DisplayName("GET /api-admin/v1/orders/{orderId}")
    @Nested
    class Get {
        @DisplayName("누구의 주문이든 구매자 id·품목(주문 당시 상품명·단가)·결제 결과·주문 시각을 반환한다.")
        @Test
        void returnsAnyOrderDetail() throws Exception {
            // arrange
            User buyer = saveUser();
            Product shirt = saveProduct("티셔츠", 3_000);
            Long orderId = placeOrder(buyer, new OrderItemCommand(shirt.getId(), 2));
            ConfirmedOrders.confirm(jdbcTemplate, orderId);

            // act & assert
            getAsAdmin(ORDERS + "/" + orderId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(orderId))
                .andExpect(jsonPath("$.data.userId").value(buyer.getId()))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.totalAmount").value(6_000))
                .andExpect(jsonPath("$.data.items[0].productId").value(shirt.getId()))
                .andExpect(jsonPath("$.data.items[0].productName").value("티셔츠"))
                .andExpect(jsonPath("$.data.items[0].quantity").value(2))
                .andExpect(jsonPath("$.data.items[0].unitPrice").value(3_000))
                .andExpect(jsonPath("$.data.items[0].amount").value(6_000))
                .andExpect(jsonPath("$.data.payment.paidAmount").value(6_000))
                .andExpect(jsonPath("$.data.payment.balanceAfter").doesNotExist())
                .andExpect(jsonPath("$.data.orderedAt").isNotEmpty());
        }

        @DisplayName("없는 주문은 404 NOT_FOUND 이고, orderId 가 숫자가 아니면 400 INVALID_INPUT 이다.")
        @Test
        void returnsNotFound_orBadRequest() throws Exception {
            getAsAdmin(ORDERS + "/999999")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("NOT_FOUND"))
                .andExpect(jsonPath("$.meta.message").value(containsString("주문")));
            getAsAdmin(ORDERS + "/abc")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("INVALID_INPUT"));
        }
    }
}
