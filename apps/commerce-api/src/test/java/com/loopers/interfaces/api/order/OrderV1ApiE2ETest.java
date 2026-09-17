package com.loopers.interfaces.api.order;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.point.PointHistoryJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.jayway.jsonpath.JsonPath;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OrderV1ApiE2ETest {

    private static final String ENDPOINT = "/api/v1/orders";
    private static final String USER_HEADER = "X-USER-ID";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private OrderJpaRepository orderJpaRepository;

    @Autowired
    private PointHistoryJpaRepository pointHistoryJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private UserModel user;
    private ProductModel airMax;
    private ProductModel airForce;

    @BeforeEach
    void setUp() {
        user = userJpaRepository.save(new UserModel("고객"));
        BrandModel nike = brandJpaRepository.save(new BrandModel("나이키", null));
        airMax = productJpaRepository.save(new ProductModel(nike.getId(), "에어맥스", 1_000, 10));
        airForce = productJpaRepository.save(new ProductModel(nike.getId(), "에어포스", 2_000, 10));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private String createBody(String items) {
        return "{\"items\": [" + items + "]}";
    }

    private String item(ProductModel product, int quantity) {
        return "{\"productId\": " + product.getId() + ", \"quantity\": " + quantity + "}";
    }

    private void charge(UserModel owner, long amount) throws Exception {
        mockMvc.perform(post("/api/v1/points/charge").header(USER_HEADER, owner.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": " + amount + "}"))
            .andExpect(status().isOk());
    }

    private Long createOrder(UserModel owner) throws Exception {
        String response = mockMvc.perform(post(ENDPOINT).header(USER_HEADER, owner.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(item(airMax, 1))))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return JsonPath.parse(response).read("$.data.id", Long.class);
    }

    @DisplayName("POST /api/v1/orders")
    @Nested
    class Create {

        @DisplayName("ORD-01 같은 상품을 합산해 DRAFT로 저장하고 합계를 돌려주며, 재고·포인트는 차감하지 않는다.")
        @Test
        void createsDraftOrder() throws Exception {
            // act
            mockMvc.perform(post(ENDPOINT).header(USER_HEADER, user.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody(item(airMax, 2) + "," + item(airMax, 3) + "," + item(airForce, 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.totalAmount").value(7_000))
                .andExpect(jsonPath("$.data.paidAmount").doesNotExist());

            // assert
            assertThat(orderJpaRepository.count()).isEqualTo(1);
            assertThat(productJpaRepository.findById(airMax.getId()).orElseThrow().getStock()).isEqualTo(10);
            assertThat(pointHistoryJpaRepository.count()).isZero();
        }

        @DisplayName("ORD-01 품목이 없거나 수량이 0이면 400이고, 주문이 저장되지 않는다.")
        @Test
        void rejectsInvalidLines() throws Exception {
            // act
            mockMvc.perform(post(ENDPOINT).header(USER_HEADER, user.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody("")))
                .andExpect(status().isBadRequest());
            mockMvc.perform(post(ENDPOINT).header(USER_HEADER, user.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody(item(airMax, 0))))
                .andExpect(status().isBadRequest());

            // assert
            assertThat(orderJpaRepository.count()).isZero();
        }

        @DisplayName("ORD-01 삭제된 상품이나 없는 상품이 있으면 404이고, 주문이 저장되지 않는다.")
        @Test
        void rejectsUnsellableProducts() throws Exception {
            // arrange
            airForce.delete();
            productJpaRepository.save(airForce);

            // act
            mockMvc.perform(post(ENDPOINT).header(USER_HEADER, user.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody(item(airMax, 1) + "," + item(airForce, 1))))
                .andExpect(status().isNotFound());
            mockMvc.perform(post(ENDPOINT).header(USER_HEADER, user.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody("{\"productId\": 999, \"quantity\": 1}")))
                .andExpect(status().isNotFound());

            // assert
            assertThat(orderJpaRepository.count()).isZero();
        }

        @DisplayName("USR-01 X-USER-ID가 없으면 401이고, 주문이 저장되지 않는다.")
        @Test
        void rejectsUnidentifiedRequest() throws Exception {
            // act
            mockMvc.perform(post(ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody(item(airMax, 1))))
                .andExpect(status().isUnauthorized());

            // assert
            assertThat(orderJpaRepository.count()).isZero();
        }
    }

    @DisplayName("GET /api/v1/orders, /api/v1/orders/{orderId}")
    @Nested
    class GetMyOrders {

        @DisplayName("ORD-06 내 주문만 최신순으로 돌려준다.")
        @Test
        void returnsOnlyMyOrdersNewestFirst() throws Exception {
            // arrange
            UserModel other = userJpaRepository.save(new UserModel("다른 고객"));
            Long first = createOrder(user);
            createOrder(other);
            Long second = createOrder(user);

            // act & assert
            mockMvc.perform(get(ENDPOINT).header(USER_HEADER, user.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[0].id").value(second))
                .andExpect(jsonPath("$.data.content[1].id").value(first))
                .andExpect(jsonPath("$.data.content[0].status").value("DRAFT"));
        }

        @DisplayName("ORD-06 내 주문 상세는 품목·수량·금액·상태를 돌려준다.")
        @Test
        void returnsMyOrderDetail() throws Exception {
            // arrange
            Long orderId = createOrder(user);

            // act & assert
            mockMvc.perform(get(ENDPOINT + "/" + orderId).header(USER_HEADER, user.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].productName").value("에어맥스"))
                .andExpect(jsonPath("$.data.items[0].unitPrice").value(1_000))
                .andExpect(jsonPath("$.data.items[0].quantity").value(1))
                .andExpect(jsonPath("$.data.items[0].amount").value(1_000))
                .andExpect(jsonPath("$.data.totalAmount").value(1_000))
                .andExpect(jsonPath("$.data.userId").doesNotExist());
        }

        @DisplayName("ORD-06 남의 주문이나 없는 주문의 상세는 404다.")
        @Test
        void hidesOthersOrders() throws Exception {
            // arrange
            UserModel other = userJpaRepository.save(new UserModel("다른 고객"));
            Long othersOrder = createOrder(other);

            // act & assert
            mockMvc.perform(get(ENDPOINT + "/" + othersOrder).header(USER_HEADER, user.getId()))
                .andExpect(status().isNotFound());
            mockMvc.perform(get(ENDPOINT + "/999").header(USER_HEADER, user.getId()))
                .andExpect(status().isNotFound());
        }
    }

    @DisplayName("POST /api/v1/orders/{orderId}/confirm")
    @Nested
    class Confirm {

        @DisplayName("ORD-05 잔액 10,000원으로 1,000원 주문을 확정하면 200이고, CONFIRMED·결제액·결제 수단·확정 시각을 돌려준다.")
        @Test
        void confirmsDraftOrder() throws Exception {
            // arrange
            charge(user, 10_000);
            Long orderId = createOrder(user);

            // act & assert
            mockMvc.perform(post(ENDPOINT + "/" + orderId + "/confirm").header(USER_HEADER, user.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(orderId))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.totalAmount").value(1_000))
                .andExpect(jsonPath("$.data.paidAmount").value(1_000))
                .andExpect(jsonPath("$.data.paymentMethod").value("POINT"))
                .andExpect(jsonPath("$.data.confirmedAt").exists());
        }

        @DisplayName("USR-01 X-USER-ID가 없으면 401이고, 주문은 DRAFT 그대로다.")
        @Test
        void rejectsUnidentifiedRequest() throws Exception {
            // arrange
            charge(user, 10_000);
            Long orderId = createOrder(user);

            // act & assert
            mockMvc.perform(post(ENDPOINT + "/" + orderId + "/confirm"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.meta.errorCode").value("Unauthorized"));
            mockMvc.perform(get(ENDPOINT + "/" + orderId).header(USER_HEADER, user.getId()))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
        }

        @DisplayName("ORD-02 남의 주문·없는 주문·남의 확정된 주문을 확정하면 모두 404다 (상태를 드러내지 않는다).")
        @Test
        void hidesOthersOrder() throws Exception {
            // arrange
            UserModel other = userJpaRepository.save(new UserModel("다른 고객"));
            charge(user, 10_000);
            charge(other, 10_000);
            Long othersOrder = createOrder(other);
            Long othersConfirmedOrder = createOrder(other);
            mockMvc.perform(post(ENDPOINT + "/" + othersConfirmedOrder + "/confirm").header(USER_HEADER, other.getId()))
                .andExpect(status().isOk());

            // act & assert
            mockMvc.perform(post(ENDPOINT + "/" + othersOrder + "/confirm").header(USER_HEADER, user.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("Not Found"));
            mockMvc.perform(post(ENDPOINT + "/999/confirm").header(USER_HEADER, user.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("Not Found"));
            mockMvc.perform(post(ENDPOINT + "/" + othersConfirmedOrder + "/confirm").header(USER_HEADER, user.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("Not Found"));
        }

        @DisplayName("ORD-04 잔액이 0원이면 409이고, 다시 조회해도 주문은 DRAFT·잔액은 0원·재고는 그대로다.")
        @Test
        void rejectsWhenBalanceIsShortAndKeepsState() throws Exception {
            // arrange
            Long orderId = createOrder(user);

            // act & assert
            mockMvc.perform(post(ENDPOINT + "/" + orderId + "/confirm").header(USER_HEADER, user.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.meta.errorCode").value("Conflict"));
            mockMvc.perform(get(ENDPOINT + "/" + orderId).header(USER_HEADER, user.getId()))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.paidAmount").doesNotExist());
            mockMvc.perform(get("/api/v1/points").header(USER_HEADER, user.getId()))
                .andExpect(jsonPath("$.data.balance").value(0));
            assertThat(productJpaRepository.findById(airMax.getId()).orElseThrow().getStock()).isEqualTo(10);
        }

        @DisplayName("ORD-02·P-17 이미 확정한 주문을 다시 확정하면 409다.")
        @Test
        void rejectsConfirmingTwice() throws Exception {
            // arrange
            charge(user, 10_000);
            Long orderId = createOrder(user);
            mockMvc.perform(post(ENDPOINT + "/" + orderId + "/confirm").header(USER_HEADER, user.getId()))
                .andExpect(status().isOk());

            // act & assert
            mockMvc.perform(post(ENDPOINT + "/" + orderId + "/confirm").header(USER_HEADER, user.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.meta.errorCode").value("Conflict"));
        }
    }
}
