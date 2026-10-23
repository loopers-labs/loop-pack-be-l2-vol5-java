package com.loopers.interfaces.api.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.order.OrderStatus;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 콜백은 인증 헤더 없이 부름 */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentV1ApiE2ETest {

    private static final String CALLBACK_PATH = "/api/v1/payments/callback";

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
    private Product product;

    @BeforeEach
    void setUp() {
        pg.reset();
        user = userJpaRepository.save(new User());
        Brand brand = brandJpaRepository.save(new Brand("브랜드", null));
        product = new Product(brand, "상품", 1_000L);
        product.changeStock(10);
        product = productJpaRepository.save(product);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    /** 주문 생성 → 확정으로 PG 결제를 시작함 */
    private Long startPayment(int quantity) throws Exception {
        String created = mvc.perform(post("/api/v1/orders")
                .header("X-USER-ID", String.valueOf(user.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\": [{\"productId\": " + product.getId() + ", \"quantity\": " + quantity + "}]}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        Long orderId = objectMapper.readTree(created).at("/data/id").asLong();
        mvc.perform(post("/api/v1/orders/" + orderId + "/confirm")
                .header("X-USER-ID", String.valueOf(user.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentAttemptKey\": \"attempt-1\", \"cardType\": \"TEST\", \"cardNo\": \"0000-0000-0000-0000\"}"))
            .andExpect(status().isAccepted());
        return orderId;
    }

    private String transactionKeyOf(Long orderId) {
        return pg.transactionsOf(Payment.providerOrderIdOf(orderId)).get(0).transactionKey();
    }

    private ResultActions callback(String body) throws Exception {
        return mvc.perform(post(CALLBACK_PATH).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String bodyOf(String providerOrderId, String transactionKey, String status) {
        return "{\"orderId\": \"" + providerOrderId + "\", \"transactionKey\": \"" + transactionKey + "\", \"status\": \"" + status + "\"}";
    }

    private OrderStatus orderStatusOf(Long orderId) {
        return orderJpaRepository.findById(orderId).orElseThrow().getStatus();
    }

    private int stock() {
        return productJpaRepository.findById(product.getId()).orElseThrow().getStock();
    }

    @DisplayName("POST /api/v1/payments/callback")
    @Nested
    class Callback {
        @DisplayName("PG 거래가 SUCCESS 면, 200 이고 주문이 CONFIRMED 가 된다.")
        @Test
        void confirms_whenTransactionSucceeded() throws Exception {
            // arrange
            Long orderId = startPayment(2);
            String transactionKey = transactionKeyOf(orderId);
            pg.complete(transactionKey, "SUCCESS");

            // act
            ResultActions result = callback(bodyOf(Payment.providerOrderIdOf(orderId), transactionKey, "SUCCESS"));

            // assert
            result.andExpect(status().isOk()).andExpect(jsonPath("$.meta.result").value("SUCCESS"));
            assertThat(orderStatusOf(orderId)).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(stock()).isEqualTo(8);
        }

        @DisplayName("본문만 SUCCESS 로 조작하고 PG 거래는 처리 중이면, 200 이지만 주문은 확정되지 않는다.")
        @Test
        void doesNotConfirm_whenBodyIsForged() throws Exception {
            // arrange
            Long orderId = startPayment(2);

            // act
            ResultActions result = callback(bodyOf(Payment.providerOrderIdOf(orderId), transactionKeyOf(orderId), "SUCCESS"));

            // assert
            result.andExpect(status().isOk());
            assertThat(orderStatusOf(orderId)).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(stock()).isEqualTo(8);
        }

        @DisplayName("본문만 FAILED 로 조작하고 PG 거래는 SUCCESS 면, 실패로 처리하지 않고 확정한다.")
        @Test
        void confirms_whenBodyClaimsFailureButTransactionSucceeded() throws Exception {
            // arrange
            Long orderId = startPayment(2);
            String transactionKey = transactionKeyOf(orderId);
            pg.complete(transactionKey, "SUCCESS");

            // act
            ResultActions result = callback(bodyOf(Payment.providerOrderIdOf(orderId), transactionKey, "FAILED"));

            // assert
            result.andExpect(status().isOk());
            assertThat(orderStatusOf(orderId)).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(stock()).isEqualTo(8);
        }

        @DisplayName("모르는 주문 식별자면, 404 PAYMENT_NOT_FOUND 이다.")
        @Test
        void returnsNotFound_whenProviderOrderIdIsUnknown() throws Exception {
            callback(bodyOf(Payment.providerOrderIdOf(999L), "TX-UNKNOWN", "SUCCESS"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("PAYMENT_NOT_FOUND"));
        }

        @DisplayName("본문에 주문 식별자가 없으면, 400 INVALID_PAYMENT_REQUEST 이다.")
        @Test
        void returnsBadRequest_whenProviderOrderIdIsMissing() throws Exception {
            callback("{\"transactionKey\": \"TX\", \"status\": \"SUCCESS\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("INVALID_PAYMENT_REQUEST"));
        }
    }
}
