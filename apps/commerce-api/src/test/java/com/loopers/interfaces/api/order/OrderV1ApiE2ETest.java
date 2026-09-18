package com.loopers.interfaces.api.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.brand.Brand;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OrderV1ApiE2ETest {

    private static final String USER_ID_HEADER = "X-USER-ID";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private OrderJpaRepository orderJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private User user;
    private Product first;
    private Product second;

    @BeforeEach
    void setUp() {
        user = userJpaRepository.save(new User());
        Brand brand = brandJpaRepository.save(new Brand("브랜드", null));
        first = saveProduct(brand, "첫 번째 상품", 1_000L, 10);
        second = saveProduct(brand, "두 번째 상품", 2_500L, 5);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Product saveProduct(Brand brand, String name, long price, int stock) {
        Product product = new Product(brand, name, price);
        product.changeStock(stock);
        return productJpaRepository.save(product);
    }

    private int stockOf(Product product) {
        return productJpaRepository.findById(product.getId()).orElseThrow().getStock();
    }

    private ResultActions postAs(User requester, String path, String body) throws Exception {
        return mvc.perform(post(path)
            .header(USER_ID_HEADER, String.valueOf(requester.getId()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }

    private ResultActions createOrder(String body) throws Exception {
        return postAs(user, "/api/v1/orders", body);
    }

    private Long createOrderId(String body) throws Exception {
        String response = createOrder(body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(response);
        return node.at("/data/id").asLong();
    }

    private ResultActions confirm(User requester, Long orderId) throws Exception {
        return postAs(requester, "/api/v1/orders/" + orderId + "/confirm", "");
    }

    private void charge(long amount) throws Exception {
        postAs(user, "/api/v1/points/charge", "{\"amount\": " + amount + "}").andExpect(status().isOk());
    }

    private String itemsOf(Object... productIdAndQuantity) {
        StringBuilder builder = new StringBuilder("{\"items\": [");
        for (int i = 0; i < productIdAndQuantity.length; i += 2) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append("{\"productId\": ").append(productIdAndQuantity[i])
                .append(", \"quantity\": ").append(productIdAndQuantity[i + 1]).append("}");
        }
        return builder.append("]}").toString();
    }

    @DisplayName("POST /api/v1/orders")
    @Nested
    class Create {
        @DisplayName("같은 상품을 합산한 DRAFT 를 돌려주고, 재고는 차감하지 않는다.")
        @Test
        void createsDraft() throws Exception {
            createOrder(itemsOf(first.getId(), 1, second.getId(), 2, first.getId(), 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.totalAmount").value(7_000))
                .andExpect(jsonPath("$.data.paymentAmount").doesNotExist())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].productId").value(first.getId()))
                .andExpect(jsonPath("$.data.items[0].quantity").value(2))
                .andExpect(jsonPath("$.data.items[0].productName").value("첫 번째 상품"))
                .andExpect(jsonPath("$.data.items[1].amount").value(5_000));

            assertThat(stockOf(first)).isEqualTo(10);
        }

        @DisplayName("없는 상품이면, 404 와 PRODUCT_NOT_FOUND, 그 상품 식별자를 돌려주고 주문이 만들어지지 않는다.")
        @Test
        void returnsProductNotFound() throws Exception {
            createOrder(itemsOf(first.getId(), 1, 999_999, 1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("PRODUCT_NOT_FOUND"))
                .andExpect(jsonPath("$.data.productId").value(999_999));

            assertThat(orderJpaRepository.count()).isZero();
        }

        @DisplayName("품목이 비어 있거나 없으면, 400 과 EMPTY_ORDER_ITEMS 를 돌려준다.")
        @ParameterizedTest
        @ValueSource(strings = {"{\"items\": []}", "{}"})
        void rejectsEmptyItems(String body) throws Exception {
            createOrder(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("EMPTY_ORDER_ITEMS"));
        }

        @DisplayName("합산한 수량이 999 를 넘으면, 400 과 INVALID_QUANTITY 를 돌려준다.")
        @Test
        void rejectsQuantityOverLimit() throws Exception {
            createOrder(itemsOf(first.getId(), 600, first.getId(), 600))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("INVALID_QUANTITY"));
        }

        @DisplayName("수량이 소수면, 400 과 BAD_REQUEST 를 돌려준다.")
        @Test
        void rejectsFractionalQuantity() throws Exception {
            createOrder(itemsOf(first.getId(), 1.5))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("BAD_REQUEST"));
        }

        @DisplayName("식별이 없으면, 401 과 UNAUTHENTICATED 를 돌려준다.")
        @Test
        void returnsUnauthenticated() throws Exception {
            mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(itemsOf(first.getId(), 1)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.meta.errorCode").value("UNAUTHENTICATED"));
        }
    }

    @DisplayName("POST /api/v1/orders/{orderId}/confirm")
    @Nested
    class Confirm {
        @DisplayName("확정하면 결제액 · 결제 시각을 담은 CONFIRMED 를 돌려주고, 재고와 잔액이 줄어 저장된다.")
        @Test
        void confirmsAndPersists() throws Exception {
            charge(10_000L);
            Long orderId = createOrderId(itemsOf(first.getId(), 2, second.getId(), 2));

            confirm(user, orderId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.paymentAmount").value(7_000))
                .andExpect(jsonPath("$.data.paidAt").isNotEmpty());

            mvc.perform(get("/api/v1/points").header(USER_ID_HEADER, String.valueOf(user.getId())))
                .andExpect(jsonPath("$.data.balance").value(3_000));
            assertThat(stockOf(first)).isEqualTo(8);
            assertThat(stockOf(second)).isEqualTo(3);
        }

        @DisplayName("생성 응답과 DB 에서 다시 읽은 확정 응답의 주문 시각이 같은 시간대(+09:00) 표기로 나간다.")
        @Test
        void writesSameTimeZone_forFreshAndReloadedEntity() throws Exception {
            charge(10_000L);
            String created = createOrder(itemsOf(first.getId(), 1)).andReturn().getResponse().getContentAsString();
            JsonNode createdNode = objectMapper.readTree(created);
            Long orderId = createdNode.at("/data/id").asLong();

            String confirmed = confirm(user, orderId).andReturn().getResponse().getContentAsString();
            JsonNode confirmedNode = objectMapper.readTree(confirmed);

            String createdOrderedAt = createdNode.at("/data/orderedAt").asText();
            assertThat(createdOrderedAt).endsWith("+09:00");
            assertThat(confirmedNode.at("/data/orderedAt").asText()).isEqualTo(createdOrderedAt);
            assertThat(confirmedNode.at("/data/paidAt").asText()).endsWith("+09:00");
        }

        @DisplayName("재고가 부족하면, 409 와 OUT_OF_STOCK, 그 상품 식별자를 돌려주고 재고와 잔액은 그대로다.")
        @Test
        void returnsOutOfStock() throws Exception {
            charge(100_000L);
            Long orderId = createOrderId(itemsOf(first.getId(), 1, second.getId(), 6));

            confirm(user, orderId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.meta.errorCode").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.data.productId").value(second.getId()));

            mvc.perform(get("/api/v1/points").header(USER_ID_HEADER, String.valueOf(user.getId())))
                .andExpect(jsonPath("$.data.balance").value(100_000));
            assertThat(stockOf(first)).isEqualTo(10);
            assertThat(orderJpaRepository.findById(orderId).orElseThrow().isDraft()).isTrue();
        }

        @DisplayName("잔액이 부족하면, 409 와 INSUFFICIENT_POINT 를 돌려준다.")
        @Test
        void returnsInsufficientPoint() throws Exception {
            Long orderId = createOrderId(itemsOf(first.getId(), 1));

            confirm(user, orderId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.meta.errorCode").value("INSUFFICIENT_POINT"));
        }

        @DisplayName("타인의 주문이면 404 와 ORDER_NOT_FOUND, 이미 확정한 주문이면 409 와 ORDER_ALREADY_CONFIRMED 를 돌려준다.")
        @Test
        void rejectsOthersOrderAndReconfirmation() throws Exception {
            charge(10_000L);
            Long orderId = createOrderId(itemsOf(first.getId(), 1));
            User other = userJpaRepository.save(new User());

            confirm(other, orderId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("ORDER_NOT_FOUND"));

            confirm(user, orderId).andExpect(status().isOk());
            confirm(user, orderId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.meta.errorCode").value("ORDER_ALREADY_CONFIRMED"));
            assertThat(stockOf(first)).isEqualTo(9);
        }
    }

    @DisplayName("GET /api/v1/orders")
    @Nested
    class GetMyOrders {
        @DisplayName("status 를 생략하면 CONFIRMED 만, DRAFT 를 주면 확정 전 주문만 돌려준다.")
        @Test
        void filtersByStatus() throws Exception {
            charge(10_000L);
            Long confirmedId = createOrderId(itemsOf(first.getId(), 1));
            confirm(user, confirmedId).andExpect(status().isOk());
            Long draftId = createOrderId(itemsOf(second.getId(), 1));

            mvc.perform(get("/api/v1/orders").header(USER_ID_HEADER, String.valueOf(user.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(confirmedId))
                .andExpect(jsonPath("$.data.items[0].itemCount").value(1))
                .andExpect(jsonPath("$.data.items[0].representativeProductName").value("첫 번째 상품"))
                .andExpect(jsonPath("$.data.items[0].paymentAmount").value(1_000));

            mvc.perform(get("/api/v1/orders").param("status", "DRAFT").header(USER_ID_HEADER, String.valueOf(user.getId())))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(draftId));
        }

        @DisplayName("모르는 status 면, 400 과 BAD_REQUEST 를 돌려준다.")
        @Test
        void rejectsUnknownStatus() throws Exception {
            mvc.perform(get("/api/v1/orders").param("status", "PAID").header(USER_ID_HEADER, String.valueOf(user.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("BAD_REQUEST"));
        }

        @DisplayName("다른 사용자의 주문은 목록에 나오지 않는다.")
        @Test
        void excludesOthersOrders() throws Exception {
            createOrderId(itemsOf(first.getId(), 1));
            User other = userJpaRepository.save(new User());

            mvc.perform(get("/api/v1/orders").param("status", "DRAFT").header(USER_ID_HEADER, String.valueOf(other.getId())))
                .andExpect(jsonPath("$.data.totalCount").value(0));
        }
    }

    @DisplayName("GET /api/v1/orders/{orderId}")
    @Nested
    class GetMyOrder {
        @DisplayName("내 주문이면 품목 전체를 돌려주고, 타인의 주문이면 404 와 ORDER_NOT_FOUND 를 돌려준다.")
        @Test
        void returnsOwnOrderOnly() throws Exception {
            Long orderId = createOrderId(itemsOf(first.getId(), 2, second.getId(), 1));
            User other = userJpaRepository.save(new User());

            mvc.perform(get("/api/v1/orders/" + orderId).header(USER_ID_HEADER, String.valueOf(user.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].productName").value("첫 번째 상품"))
                .andExpect(jsonPath("$.data.items[0].unitPrice").value(1_000))
                .andExpect(jsonPath("$.data.items[0].amount").value(2_000))
                .andExpect(jsonPath("$.data.userId").doesNotExist());

            mvc.perform(get("/api/v1/orders/" + orderId).header(USER_ID_HEADER, String.valueOf(other.getId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("ORDER_NOT_FOUND"));
        }
    }
}
