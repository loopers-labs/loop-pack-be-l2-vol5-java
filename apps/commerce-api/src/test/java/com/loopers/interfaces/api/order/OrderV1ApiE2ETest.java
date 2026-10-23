package com.loopers.interfaces.api.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.payment.entity.Payment;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.pg.TestPgServer;
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

    private final TestPgServer pg = TestPgServer.shared();
    private User user;
    private Product first;
    private Product second;

    @BeforeEach
    void setUp() {
        pg.reset();
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

    private ResultActions confirm(User requester, Long orderId, String attemptKey) throws Exception {
        return postAs(requester, "/api/v1/orders/" + orderId + "/confirm",
            "{\"paymentAttemptKey\": \"" + attemptKey + "\", \"cardType\": \"TEST\", \"cardNo\": \"0000-0000-0000-0000\"}");
    }

    private ResultActions reconcile(User requester, Long orderId) throws Exception {
        return postAs(requester, "/api/v1/orders/" + orderId + "/payment/reconcile", "");
    }

    private void completeTransactionOf(Long orderId, String status) {
        String transactionKey = pg.transactionsOf(Payment.providerOrderIdOf(orderId)).get(0).transactionKey();
        pg.complete(transactionKey, status);
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
        @DisplayName("결제를 시작하면 202 와 PAYMENT_PENDING · PENDING · 조회 경로를 돌려주고, 재고를 확보하며 포인트는 그대로다.")
        @Test
        void startsPaymentAndReservesStock() throws Exception {
            charge(10_000L);
            Long orderId = createOrderId(itemsOf(first.getId(), 2, second.getId(), 2));

            confirm(user, orderId, "attempt-1")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("PAYMENT_PENDING"))
                .andExpect(jsonPath("$.data.paymentMethod").value("PG"))
                .andExpect(jsonPath("$.data.paymentStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.statusUrl").value("/api/v1/orders/" + orderId))
                .andExpect(jsonPath("$.data.paymentAmount").doesNotExist())
                .andExpect(jsonPath("$.data.paidAt").doesNotExist());

            mvc.perform(get("/api/v1/points").header(USER_ID_HEADER, String.valueOf(user.getId())))
                .andExpect(jsonPath("$.data.balance").value(10_000));
            assertThat(stockOf(first)).isEqualTo(8);
            assertThat(stockOf(second)).isEqualTo(3);
            assertThat(pg.createCount()).isEqualTo(1);
        }

        @DisplayName("생성 응답과 DB 에서 다시 읽은 결과 확인 응답의 주문 시각이 같은 시간대(+09:00) 표기로 나간다.")
        @Test
        void writesSameTimeZone_forFreshAndReloadedEntity() throws Exception {
            String created = createOrder(itemsOf(first.getId(), 1)).andReturn().getResponse().getContentAsString();
            JsonNode createdNode = objectMapper.readTree(created);
            Long orderId = createdNode.at("/data/id").asLong();
            confirm(user, orderId, "attempt-1").andExpect(status().isAccepted());
            completeTransactionOf(orderId, "SUCCESS");

            String confirmed = reconcile(user, orderId).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            JsonNode confirmedNode = objectMapper.readTree(confirmed);

            String createdOrderedAt = createdNode.at("/data/orderedAt").asText();
            assertThat(createdOrderedAt).endsWith("+09:00");
            assertThat(confirmedNode.at("/data/orderedAt").asText()).isEqualTo(createdOrderedAt);
            assertThat(confirmedNode.at("/data/paidAt").asText()).endsWith("+09:00");
        }

        @DisplayName("재고가 부족하면, 409 와 OUT_OF_STOCK, 그 상품 식별자를 돌려주고 먼저 확보한 재고도 롤백되며 PG 를 부르지 않는다.")
        @Test
        void returnsOutOfStock() throws Exception {
            Long orderId = createOrderId(itemsOf(first.getId(), 1, second.getId(), 6));

            confirm(user, orderId, "attempt-1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.meta.errorCode").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.data.productId").value(second.getId()));

            assertThat(stockOf(first)).isEqualTo(10);
            assertThat(orderJpaRepository.findById(orderId).orElseThrow().isDraft()).isTrue();
            assertThat(pg.createCount()).isZero();
        }

        @DisplayName("PG 가 효과 0건으로 확정 거절하면, 200 과 PAYMENT_FAILED · REJECTED 를 돌려주고 확보한 재고를 반환한다.")
        @Test
        void returnsPaymentFailed_whenRejectedWithoutEffect() throws Exception {
            pg.willRejectBeforeCreate();
            Long orderId = createOrderId(itemsOf(first.getId(), 2));

            confirm(user, orderId, "attempt-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAYMENT_FAILED"))
                .andExpect(jsonPath("$.data.paymentStatus").value("REJECTED"));

            assertThat(stockOf(first)).isEqualTo(10);
        }

        @DisplayName("같은 시도 키로 다시 보내면 202 와 저장된 상태를, 다른 키면 409 와 PAYMENT_ATTEMPT_CONFLICT 를 돌려주고 재고 확보 · PG 생성은 한 번이다.")
        @Test
        void returnsStoredStateOrConflict_onRepeatedConfirm() throws Exception {
            Long orderId = createOrderId(itemsOf(first.getId(), 1));
            confirm(user, orderId, "attempt-1").andExpect(status().isAccepted());

            confirm(user, orderId, "attempt-1")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.paymentStatus").value("PENDING"));
            confirm(user, orderId, "attempt-2")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.meta.errorCode").value("PAYMENT_ATTEMPT_CONFLICT"));

            assertThat(stockOf(first)).isEqualTo(9);
            assertThat(pg.createCount()).isEqualTo(1);
        }

        @DisplayName("0원 주문이면, PG 를 부르지 않고 200 과 CONFIRMED · 결제수단 NONE · NOT_REQUIRED 를 돌려준다. (D-68)")
        @Test
        void confirmsZeroAmountOrderWithoutPg() throws Exception {
            Product free = saveProduct(brandJpaRepository.save(new Brand("무료 브랜드", null)), "무료 상품", 0L, 5);
            Long orderId = createOrderId(itemsOf(free.getId(), 2));

            confirm(user, orderId, "attempt-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.paymentMethod").value("NONE"))
                .andExpect(jsonPath("$.data.paymentStatus").value("NOT_REQUIRED"))
                .andExpect(jsonPath("$.data.paymentAmount").value(0));

            assertThat(stockOf(free)).isEqualTo(3);
            assertThat(pg.createCount()).isZero();
        }

        @DisplayName("타인의 주문이면 404 와 ORDER_NOT_FOUND 를 돌려준다.")
        @Test
        void rejectsOthersOrder() throws Exception {
            Long orderId = createOrderId(itemsOf(first.getId(), 1));
            User other = userJpaRepository.save(new User());

            confirm(other, orderId, "attempt-1")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("ORDER_NOT_FOUND"));
            assertThat(pg.createCount()).isZero();
        }

        @DisplayName("시도 키나 카드 값이 비었으면, 400 과 INVALID_PAYMENT_REQUEST 를 돌려주고 재고를 확보하지 않는다.")
        @ParameterizedTest
        @ValueSource(strings = {
            "{}",
            "{\"paymentAttemptKey\": \"attempt-1\", \"cardType\": \"TEST\"}",
            "{\"paymentAttemptKey\": \"\", \"cardType\": \"TEST\", \"cardNo\": \"0000-0000-0000-0000\"}"
        })
        void rejectsInvalidPaymentRequest(String body) throws Exception {
            Long orderId = createOrderId(itemsOf(first.getId(), 1));

            postAs(user, "/api/v1/orders/" + orderId + "/confirm", body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("INVALID_PAYMENT_REQUEST"));
            assertThat(stockOf(first)).isEqualTo(10);
        }
    }

    @DisplayName("POST /api/v1/orders/{orderId}/payment/reconcile")
    @Nested
    class Reconcile {
        @DisplayName("PG 거래가 SUCCESS 면 200 과 CONFIRMED · 결제액 · 결제 시각을 돌려주고, 재고는 더 줄지 않는다.")
        @Test
        void confirmsOrder_whenTransactionSucceeded() throws Exception {
            Long orderId = createOrderId(itemsOf(first.getId(), 2));
            confirm(user, orderId, "attempt-1").andExpect(status().isAccepted());
            completeTransactionOf(orderId, "SUCCESS");

            reconcile(user, orderId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.paymentStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.data.paymentAmount").value(2_000))
                .andExpect(jsonPath("$.data.paidAt").isNotEmpty());

            assertThat(stockOf(first)).isEqualTo(8);
            assertThat(pg.createCount()).isEqualTo(1);
        }

        @DisplayName("PG 거래가 아직 처리 중이면 202 와 PAYMENT_PENDING 을 돌려준다.")
        @Test
        void keepsPending_whenTransactionIsPending() throws Exception {
            Long orderId = createOrderId(itemsOf(first.getId(), 1));
            confirm(user, orderId, "attempt-1").andExpect(status().isAccepted());

            reconcile(user, orderId)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("PAYMENT_PENDING"))
                .andExpect(jsonPath("$.data.paymentCheck").doesNotExist());
        }

        @DisplayName("PG 를 조회하지 못하면 202 와 저장된 상태, paymentCheck=UNAVAILABLE 을 돌려주고 재고를 반환하지 않는다.")
        @Test
        void returnsStoredState_whenLookupIsUnavailable() throws Exception {
            Long orderId = createOrderId(itemsOf(first.getId(), 1));
            confirm(user, orderId, "attempt-1").andExpect(status().isAccepted());
            pg.lookupWillRespond(500, "{}");

            reconcile(user, orderId)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("PAYMENT_PENDING"))
                .andExpect(jsonPath("$.data.paymentStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.paymentCheck").value("UNAVAILABLE"));

            assertThat(stockOf(first)).isEqualTo(9);
        }

        @DisplayName("결제를 시작하지 않은 주문이면 404 와 PAYMENT_NOT_FOUND, 타인의 주문이면 404 와 ORDER_NOT_FOUND 를 돌려준다.")
        @Test
        void rejectsOrderWithoutAttemptOrOthersOrder() throws Exception {
            Long draftId = createOrderId(itemsOf(first.getId(), 1));
            User other = userJpaRepository.save(new User());

            reconcile(user, draftId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("PAYMENT_NOT_FOUND"));
            reconcile(other, draftId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("ORDER_NOT_FOUND"));
        }
    }

    @DisplayName("GET /api/v1/orders")
    @Nested
    class GetMyOrders {
        @DisplayName("status 를 생략하면 CONFIRMED 만, DRAFT 를 주면 확정 전 주문만 돌려준다.")
        @Test
        void filtersByStatus() throws Exception {
            Long confirmedId = createOrderId(itemsOf(first.getId(), 1));
            confirm(user, confirmedId, "attempt-1").andExpect(status().isAccepted());
            completeTransactionOf(confirmedId, "SUCCESS");
            reconcile(user, confirmedId).andExpect(status().isOk());
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

        @DisplayName("각 줄에 결제수단 · 결제 상태를 담고, 결제 진행 중인 주문은 PAYMENT_PENDING 으로 거를 수 있다. (5주차 설계 7장)")
        @Test
        void returnsPaymentFields() throws Exception {
            Long confirmedId = createOrderId(itemsOf(first.getId(), 1));
            confirm(user, confirmedId, "attempt-1").andExpect(status().isAccepted());
            completeTransactionOf(confirmedId, "SUCCESS");
            reconcile(user, confirmedId).andExpect(status().isOk());
            Long pendingId = createOrderId(itemsOf(second.getId(), 1));
            confirm(user, pendingId, "attempt-2").andExpect(status().isAccepted());

            mvc.perform(get("/api/v1/orders").header(USER_ID_HEADER, String.valueOf(user.getId())))
                .andExpect(jsonPath("$.data.items[0].id").value(confirmedId))
                .andExpect(jsonPath("$.data.items[0].paymentMethod").value("PG"))
                .andExpect(jsonPath("$.data.items[0].paymentStatus").value("SUCCESS"));
            mvc.perform(get("/api/v1/orders").param("status", "PAYMENT_PENDING").header(USER_ID_HEADER, String.valueOf(user.getId())))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(pendingId))
                .andExpect(jsonPath("$.data.items[0].paymentStatus").value("PENDING"));
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
                .andExpect(jsonPath("$.data.items[0].onSale").value(true))
                .andExpect(jsonPath("$.data.userId").doesNotExist());

            mvc.perform(get("/api/v1/orders/" + orderId).header(USER_ID_HEADER, String.valueOf(other.getId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("ORDER_NOT_FOUND"));
        }

        @DisplayName("주문한 상품이 이후 삭제되었으면, 그 품목의 onSale 은 false 이고 나머지는 true 다. (설계 6.4)")
        @Test
        void marksDeletedProductAsNotOnSale() throws Exception {
            // arrange
            Long orderId = createOrderId(itemsOf(first.getId(), 1, second.getId(), 1));
            Product deleted = productJpaRepository.findById(second.getId()).orElseThrow();
            deleted.delete();
            productJpaRepository.save(deleted);

            // act & assert
            mvc.perform(get("/api/v1/orders/" + orderId).header(USER_ID_HEADER, String.valueOf(user.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].onSale").value(true))
                .andExpect(jsonPath("$.data.items[1].onSale").value(false))
                .andExpect(jsonPath("$.data.items[1].productName").value("두 번째 상품"));
        }

        @DisplayName("결제수단 · 결제 상태를 담고, 충돌이 기록돼도 고객에게는 충돌 사유를 보여 주지 않는다. (5주차 설계 7장)")
        @Test
        void returnsPaymentFields_withoutConflict() throws Exception {
            // arrange: 같은 주문의 거래가 2 건이라 충돌이 기록됨
            Long orderId = createOrderId(itemsOf(first.getId(), 1));
            confirm(user, orderId, "attempt-1").andExpect(status().isAccepted());
            pg.addTransaction(Payment.providerOrderIdOf(orderId), 1_000L, "SUCCESS");
            reconcile(user, orderId).andExpect(status().isAccepted());

            // act & assert
            mvc.perform(get("/api/v1/orders/" + orderId).header(USER_ID_HEADER, String.valueOf(user.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAYMENT_PENDING"))
                .andExpect(jsonPath("$.data.paymentMethod").value("PG"))
                .andExpect(jsonPath("$.data.paymentStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.paymentConflict").doesNotExist());
        }

        @DisplayName("결제를 시작하지 않은 주문이면, 결제 필드를 비워 둔다.")
        @Test
        void leavesPaymentFieldsEmpty_whenNoAttempt() throws Exception {
            Long orderId = createOrderId(itemsOf(first.getId(), 1));

            mvc.perform(get("/api/v1/orders/" + orderId).header(USER_ID_HEADER, String.valueOf(user.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentMethod").doesNotExist())
                .andExpect(jsonPath("$.data.paymentStatus").doesNotExist());
        }
    }
}
