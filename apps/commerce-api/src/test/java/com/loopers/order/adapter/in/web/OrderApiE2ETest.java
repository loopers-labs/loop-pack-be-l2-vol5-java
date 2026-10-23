package com.loopers.order.adapter.in.web;

import com.loopers.brand.adapter.out.persistence.BrandJpaRepository;
import com.loopers.brand.domain.BrandModel;
import com.loopers.order.adapter.out.persistence.OrderJpaRepository;
import com.loopers.payment.adapter.out.persistence.PaymentJpaRepository;
import com.loopers.payment.domain.Payment;
import com.loopers.payment.domain.PaymentStatus;
import com.loopers.point.adapter.out.persistence.PointHistoryJpaRepository;
import com.loopers.product.adapter.out.persistence.ProductJpaRepository;
import com.loopers.product.domain.ProductModel;
import com.loopers.support.pg.LocalTestPgServer;
import com.loopers.user.adapter.out.persistence.UserJpaRepository;
import com.loopers.user.domain.UserModel;
import com.loopers.utils.DatabaseCleanUp;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 고객 주문 API. W5부터 확정(POST /{orderId}/confirm)은 PG 결제 요청이라(W5 plan.md 8) 로컬 테스트 PG fixture를 띄워 둔다.
 * 확정 결과는 fixture에서 거래를 SUCCESS·FAILED로 끝낸 뒤 reconcile(권위 조회)로 반영한다 — callback 없이도 같은 결과 적용 경계를 지난다.
 */
// W5: PG fixture 주소(또는 발송 mock) 때문에 이 클래스만 쓰는 컨텍스트가 생긴다. 다른 클래스가 재사용하지 않으므로 끝나면 닫아
// DB 커넥션을 돌려준다 — 캐시된 컨텍스트마다 Hikari 풀을 잡아 로컬 MySQL max_connections(151)를 넘기지 않게.
@DirtiesContext
@SpringBootTest
@AutoConfigureMockMvc
class OrderApiE2ETest {

    private static final String ENDPOINT = "/api/v1/orders";
    private static final String USER_HEADER = "X-USER-ID";
    private static final String KEY_HEADER = "Idempotency-Key";
    private static final String CARD_BODY = "{\"cardType\": \"SAMSUNG\", \"cardNo\": \"1234-5678-9814-1451\"}";
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final LocalTestPgServer PG = LocalTestPgServer.start();

    @DynamicPropertySource
    static void pgProperties(DynamicPropertyRegistry registry) {
        registry.add("pg.local.base-url", PG::baseUrl);
    }

    @AfterAll
    static void stopPg() {
        PG.stop();
    }

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
    private PaymentJpaRepository paymentJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private UserModel user;
    private ProductModel airMax;
    private ProductModel airForce;

    @BeforeEach
    void setUp() {
        PG.reset();
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

    /** W5 확정 요청: Idempotency-Key 헤더 + 카드 본문 (W5 plan.md 8). */
    private MockHttpServletRequestBuilder confirm(Long orderId, UserModel requester, String key) {
        return post(ENDPOINT + "/" + orderId + "/confirm").header(USER_HEADER, requester.getId())
            .header(KEY_HEADER, key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(CARD_BODY);
    }

    /** 확정 요청(202) → PG 거래가 PENDING으로 생길 때까지 기다린 뒤, fixture에서 결과를 정하고 reconcile로 반영한다. */
    private void confirmAndComplete(Long orderId, UserModel owner, String key, String pgStatus) throws Exception {
        mockMvc.perform(confirm(orderId, owner, key)).andExpect(status().isAccepted());
        await().atMost(WAIT).until(() -> paymentOf(orderId).getStatus() == PaymentStatus.PENDING);
        PG.complete(paymentOf(orderId).getTransactionKey(), pgStatus, "SUCCESS".equals(pgStatus) ? null : "한도 초과");
        mockMvc.perform(post(ENDPOINT + "/" + orderId + "/payment/reconcile").header(USER_HEADER, owner.getId()))
            .andExpect(status().isOk());
    }

    private Payment paymentOf(Long orderId) {
        return paymentJpaRepository.findByOrderId(orderId).orElseThrow();
    }

    private int stockOf(ProductModel product) {
        return productJpaRepository.findById(product.getId()).orElseThrow().getStock();
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

        // W5 이전: 포인트로 바로 확정해 200·CONFIRMED·POINT. 이제는 PG 결제 요청이라 접수 202(PAYMENT_PENDING)이고,
        // PG 성공을 확인한 뒤에 CONFIRMED·결제액·확정 시각이 정해진다. 결제 수단은 PG, 포인트는 쓰지 않는다 (C-1).
        @DisplayName("ORD-05·C-1 1,000원 주문을 확정 요청하면 202·PAYMENT_PENDING이고, PG 성공을 확인하면 CONFIRMED·결제액·결제 수단 PG·확정 시각을 돌려주며, 잔액 10,000원은 그대로다.")
        @Test
        void confirmsDraftOrder() throws Exception {
            // arrange
            charge(user, 10_000);
            Long orderId = createOrder(user);

            // act & assert: 접수
            mockMvc.perform(confirm(orderId, user, "attempt-1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.orderId").value(orderId))
                .andExpect(jsonPath("$.data.orderStatus").value("PAYMENT_PENDING"));
            await().atMost(WAIT).until(() -> paymentOf(orderId).getStatus() == PaymentStatus.PENDING);

            // act & assert: PG 성공 확인 → 확정
            PG.complete(paymentOf(orderId).getTransactionKey(), "SUCCESS", null);
            mockMvc.perform(post(ENDPOINT + "/" + orderId + "/payment/reconcile").header(USER_HEADER, user.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderStatus").value("CONFIRMED"));
            mockMvc.perform(get(ENDPOINT + "/" + orderId).header(USER_HEADER, user.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(orderId))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.totalAmount").value(1_000))
                .andExpect(jsonPath("$.data.paidAmount").value(1_000))
                .andExpect(jsonPath("$.data.paymentMethod").value("PG"))
                .andExpect(jsonPath("$.data.confirmedAt").exists());
            mockMvc.perform(get("/api/v1/points").header(USER_HEADER, user.getId()))
                .andExpect(jsonPath("$.data.balance").value(10_000));
            assertThat(stockOf(airMax)).isEqualTo(9);
        }

        @DisplayName("USR-01 X-USER-ID가 없으면 401이고, 주문은 DRAFT 그대로다.")
        @Test
        void rejectsUnidentifiedRequest() throws Exception {
            // arrange
            charge(user, 10_000);
            Long orderId = createOrder(user);

            // act & assert: 헤더·본문은 모두 갖추고 식별만 뺀다
            mockMvc.perform(post(ENDPOINT + "/" + orderId + "/confirm")
                    .header(KEY_HEADER, "attempt-1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(CARD_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.meta.errorCode").value("Unauthorized"));
            mockMvc.perform(get(ENDPOINT + "/" + orderId).header(USER_HEADER, user.getId()))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
            assertThat(paymentJpaRepository.count()).isZero();
        }

        // W5: "남의 확정된 주문"은 이제 PG 성공으로 확정된 주문이다.
        @DisplayName("ORD-02 남의 주문·없는 주문·남의 확정된 주문을 확정하면 모두 404다 (상태를 드러내지 않는다).")
        @Test
        void hidesOthersOrder() throws Exception {
            // arrange
            UserModel other = userJpaRepository.save(new UserModel("다른 고객"));
            charge(user, 10_000);
            charge(other, 10_000);
            Long othersOrder = createOrder(other);
            Long othersConfirmedOrder = createOrder(other);
            confirmAndComplete(othersConfirmedOrder, other, "others-attempt", "SUCCESS");

            // act & assert
            mockMvc.perform(confirm(othersOrder, user, "attempt-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("Not Found"));
            mockMvc.perform(confirm(999L, user, "attempt-2"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("Not Found"));
            mockMvc.perform(confirm(othersConfirmedOrder, user, "attempt-3"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("Not Found"));
            assertThat(paymentJpaRepository.count()).isEqualTo(1);
        }

        // W5 이전: 잔액 부족이면 409·DRAFT·잔액·재고 그대로. 이제 잔액은 확정에 쓰이지 않으므로(C-1) 잔액 0원이어도 접수되고,
        // "결제할 수 없으면 아무것도 남기지 않는다"는 의도는 PG가 결제를 거절(FAILED)한 경우로 옮긴다 — 주문 PAYMENT_FAILED, 결제액 없음,
        // 선점했던 재고는 한 번 반환되어 그대로, 잔액 0원 그대로 (C-5).
        @DisplayName("ORD-04·C-1·C-5 잔액 0원이어도 결제는 PG가 하므로 접수되고, PG가 거절로 끝나면 주문 PAYMENT_FAILED·결제액 없음·잔액 0원·재고 그대로다.")
        @Test
        void rejectsWhenBalanceIsShortAndKeepsState() throws Exception {
            // arrange
            Long orderId = createOrder(user);

            // act
            confirmAndComplete(orderId, user, "attempt-1", "FAILED");

            // assert
            mockMvc.perform(get(ENDPOINT + "/" + orderId).header(USER_HEADER, user.getId()))
                .andExpect(jsonPath("$.data.status").value("PAYMENT_FAILED"))
                .andExpect(jsonPath("$.data.paidAmount").doesNotExist());
            mockMvc.perform(get("/api/v1/points").header(USER_HEADER, user.getId()))
                .andExpect(jsonPath("$.data.balance").value(0));
            assertThat(stockOf(airMax)).isEqualTo(10);
        }

        @DisplayName("ORD-01·ORD-03 같은 상품 품목(6개 + 5개)은 11개로 합산되고, 확정할 때 합산 수량으로 재고(10개)를 확인해 409이며 재고는 그대로이고 PG를 부르지 않는다.")
        @Test
        void checksStockWithMergedQuantity() throws Exception {
            // arrange
            charge(user, 20_000);
            String created = mockMvc.perform(post(ENDPOINT).header(USER_HEADER, user.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody(item(airMax, 6) + "," + item(airMax, 5))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].quantity").value(11))
                .andReturn().getResponse().getContentAsString();
            Long orderId = JsonPath.parse(created).read("$.data.id", Long.class);

            // act & assert
            mockMvc.perform(confirm(orderId, user, "attempt-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.meta.errorCode").value("Conflict"));
            assertThat(stockOf(airMax)).isEqualTo(10);
            mockMvc.perform(get(ENDPOINT + "/" + orderId).header(USER_HEADER, user.getId()))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
            assertThat(paymentJpaRepository.count()).isZero();
            assertThat(PG.createCalls()).isZero();
        }

        // W5: "이미 확정"은 PG 성공으로 확정된 주문. 다른 키로 다시 확정하면 409이고, 재고는 한 번만 빠져 있다.
        @DisplayName("ORD-02·P-17 이미 확정한 주문을 다시 확정하면 409이고, 재고는 한 번만 빠져 있으며 PG 생성도 1번이다.")
        @Test
        void rejectsConfirmingTwice() throws Exception {
            // arrange
            charge(user, 10_000);
            Long orderId = createOrder(user);
            confirmAndComplete(orderId, user, "attempt-1", "SUCCESS");

            // act & assert
            mockMvc.perform(confirm(orderId, user, "attempt-2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.meta.errorCode").value("Conflict"));
            assertThat(stockOf(airMax)).isEqualTo(9);
            assertThat(PG.createCalls()).isEqualTo(1);
        }

        // C-2: W2~W4에 포인트로 확정한 주문은 그대로 남아 있다. 이제 포인트 확정 경로가 없으므로 그 행을 SQL로 만든다.
        @DisplayName("C-2·ORD-02 이전에 포인트로 확정한 주문은 결제 수단 POINT·결제액 그대로 조회되고, 다시 확정하면 409이며 PG를 부르지 않는다.")
        @Test
        void keepsPointConfirmedOrder() throws Exception {
            // arrange
            Long orderId = createOrder(user);
            jdbcTemplate.update(
                "UPDATE orders SET status = 'CONFIRMED', payment_method = 'POINT', paid_amount = 1000, confirmed_at = NOW() WHERE id = ?", orderId);

            // act & assert
            mockMvc.perform(get(ENDPOINT + "/" + orderId).header(USER_HEADER, user.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.paymentMethod").value("POINT"))
                .andExpect(jsonPath("$.data.paidAmount").value(1_000))
                .andExpect(jsonPath("$.data.items[0].productName").value("에어맥스"));
            mockMvc.perform(confirm(orderId, user, "attempt-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.meta.errorCode").value("Conflict"));
            assertThat(paymentJpaRepository.count()).isZero();
            assertThat(PG.createCalls()).isZero();
            assertThat(stockOf(airMax)).isEqualTo(10);
        }
    }
}
