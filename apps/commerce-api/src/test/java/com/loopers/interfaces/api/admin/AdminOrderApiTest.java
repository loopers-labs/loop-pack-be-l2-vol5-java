package com.loopers.interfaces.api.admin;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static com.loopers.interfaces.api.admin.AdminRequests.adminGet;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminOrderApiTest {

    private static final String ORDERS = "/api-admin/v1/orders";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private OrderJpaRepository orderJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private User buyer;
    private User otherBuyer;
    private Product bag;
    private Product cap;

    @BeforeEach
    void setUp() {
        buyer = userJpaRepository.save(new User("user1"));
        otherBuyer = userJpaRepository.save(new User("user2"));
        Brand brand = brandJpaRepository.save(new Brand("루퍼스"));
        bag = productJpaRepository.save(new Product(brand.getId(), "가방", 3_000L, 5L));
        cap = productJpaRepository.save(new Product(brand.getId(), "모자", 1_000L, 5L));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    // 가방 3,000원×2 + 모자 1,000원×1 = 7,000원
    private Order saveOrder(User orderer) {
        return orderJpaRepository.save(new Order(orderer.getId(), List.of(new OrderItem(bag.getId(), 2L, 3_000L), new OrderItem(cap.getId(), 1L, 1_000L))));
    }

    @DisplayName("GET /api-admin/v1/orders")
    @Nested
    class GetOrders {

        @DisplayName("구매자 필터가 없으면, 모든 구매자의 주문을 최신순으로 구매자 ID와 함께 조회한다.")
        @Test
        void returnsAllBuyersOrders_inLatestOrder() throws Exception {
            // arrange
            Order first = saveOrder(buyer);
            Order second = saveOrder(otherBuyer);

            // act & assert
            mvc.perform(adminGet(ORDERS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].orderId").value(contains(second.getId().intValue(), first.getId().intValue())))
                .andExpect(jsonPath("$.data.content[*].userId").value(contains(otherBuyer.getId().intValue(), buyer.getId().intValue())))
                .andExpect(jsonPath("$.data.totalElements").value(2));
        }

        @DisplayName("userId로 필터하면, 그 구매자의 주문만 조회한다.")
        @Test
        void returnsOnlyBuyersOrders_whenUserIdIsGiven() throws Exception {
            // arrange
            Order mine = saveOrder(buyer);
            saveOrder(otherBuyer);

            // act & assert
            mvc.perform(adminGet(ORDERS).param("userId", String.valueOf(buyer.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].orderId").value(contains(mine.getId().intValue())))
                .andExpect(jsonPath("$.data.totalElements").value(1));
        }

        @DisplayName("page·size가 잘못되면, 400 BAD_REQUEST 응답을 받는다.")
        @Test
        void returnsBadRequest_whenPageIsInvalid() throws Exception {
            mvc.perform(adminGet(ORDERS).param("page", "-1"))
                .andExpect(status().isBadRequest());
        }
    }

    @DisplayName("GET /api-admin/v1/orders/{orderId}")
    @Nested
    class GetOrder {

        @DisplayName("구매자의 주문 품목·상태·금액·결제 결과를 구매자 ID와 함께 조회하고, 삭제된 상품의 이름도 보여준다. (DEL-003)")
        @Test
        void returnsOrderWithBuyer_includingDeletedProductName() throws Exception {
            // arrange
            Order order = saveOrder(buyer);
            Product deletedBag = productJpaRepository.findById(bag.getId()).orElseThrow();
            deletedBag.delete();
            productJpaRepository.save(deletedBag);

            // act & assert
            mvc.perform(adminGet(ORDERS + "/" + order.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(order.getId()))
                .andExpect(jsonPath("$.data.userId").value(buyer.getId()))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.totalAmount").value(7_000))
                .andExpect(jsonPath("$.data.paidAmount").doesNotExist())
                .andExpect(jsonPath("$.data.items[*].productName").value(containsInAnyOrder("가방", "모자")));
        }

        @DisplayName("없는 주문이면, 404 NOT_FOUND 응답을 받는다.")
        @Test
        void returnsNotFound_whenOrderDoesNotExist() throws Exception {
            mvc.perform(adminGet(ORDERS + "/999999"))
                .andExpect(status().isNotFound());
        }
    }
}
