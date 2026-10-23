package com.loopers.interfaces.api.order;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.Order;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OrderAdminV1ApiE2ETest {

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
    private PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Order givenOrder() {
        BrandModel brand = brandJpaRepository.save(new BrandModel("나이키", "스포츠 브랜드", "신발/의류"));
        ProductModel product = productJpaRepository.save(new ProductModel("에어맥스", 1000L, brand.getId(), 10));
        Long userId = userJpaRepository.save(new UserModel()).getId();
        return orderJpaRepository.save(new Order(userId, List.of(new Order.OrderItemDraft(product.getId(), 1, 1000L))));
    }

    @DisplayName("GET /api-admin/v1/orders")
    @Nested
    class GetOrders {
        @DisplayName("ADMIN 권한으로 요청하면, 200과 소유자 무관 전체 주문 목록을 반환한다.")
        @Test
        void returnsAllOrders_whenRequestedByAdmin() throws Exception {
            // arrange
            givenOrder();
            givenOrder();

            // act & assert
            mvc.perform(get("/api-admin/v1/orders")
                    .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orders.length()").value(2));
        }

        @DisplayName("USER 권한으로 요청하면, 403 FORBIDDEN 응답을 받는다.")
        @Test
        void throwsForbidden_whenRequestedByUser() throws Exception {
            // act & assert
            mvc.perform(get("/api-admin/v1/orders")
                    .with(user("customer").roles("USER"))
                    .with(csrf()))
                .andExpect(status().isForbidden());
        }
    }

    @DisplayName("GET /api-admin/v1/orders/{orderId}")
    @Nested
    class GetOrder {
        @DisplayName("ADMIN 권한으로 요청하면, 소유자와 무관하게 200과 주문 상세를 반환한다.")
        @Test
        void returnsOrder_regardlessOfOwner() throws Exception {
            // arrange
            Order order = givenOrder();

            // act & assert
            mvc.perform(get("/api-admin/v1/orders/" + order.getId())
                    .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(order.getId()));
        }

        @DisplayName("존재하지 않는 주문 ID를 주면, 404 NOT_FOUND 응답을 받는다.")
        @Test
        void throwsNotFound_whenOrderDoesNotExist() throws Exception {
            // act & assert
            mvc.perform(get("/api-admin/v1/orders/999")
                    .with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
        }

        @DisplayName("USER 권한으로 요청하면, 403 FORBIDDEN 응답을 받는다.")
        @Test
        void throwsForbidden_whenRequestedByUser() throws Exception {
            // arrange
            Order order = givenOrder();

            // act & assert
            mvc.perform(get("/api-admin/v1/orders/" + order.getId())
                    .with(user("customer").roles("USER"))
                    .with(csrf()))
                .andExpect(status().isForbidden());
        }
    }

    /** 결제 요청 중(요청됨)인 주문 — 발송 도중 서버가 죽은 흔적처럼, 거래 키를 모르는 미종결 결제다. */
    private Order givenPaymentRequestedOrder() {
        Order order = givenOrder();
        order.startPgPayment();
        Order saved = orderJpaRepository.save(order);
        paymentAttemptRepository.save(new PaymentAttempt(saved.getId(), "key-" + saved.getId(),
            String.format("LP-ORD-%010d", saved.getId()), 1000L));
        return saved;
    }

    @DisplayName("POST /api-admin/v1/orders/{orderId}/payment/fail")
    @Nested
    class FailPayment {
        @DisplayName("ADMIN 권한으로 요청하면, 200과 결제 실패 주문을 반환하고 처리자는 인증된 관리자 이름이다. (D-046)")
        @Test
        void failsPayment_andRecordsAuthenticatedAdmin() throws Exception {
            // arrange
            Order order = givenPaymentRequestedOrder();

            // act & assert
            mvc.perform(post("/api-admin/v1/orders/" + order.getId() + "/payment/fail")
                    .with(user("admin-kim").roles("ADMIN"))
                    .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(order.getId()))
                .andExpect(jsonPath("$.data.status").value("PAYMENT_FAILED"))
                .andExpect(jsonPath("$.data.payment.status").value("FAILED"))
                .andExpect(jsonPath("$.data.payment.resolvedBy").value("admin-kim"))
                .andExpect(jsonPath("$.data.payment.watchUntil").exists())
                .andExpect(jsonPath("$.data.conflicts").isArray());
        }

        @DisplayName("USER 권한으로 요청하면, 403 FORBIDDEN 응답을 받고 결제는 그대로다.")
        @Test
        void throwsForbidden_whenRequestedByUser() throws Exception {
            // arrange
            Order order = givenPaymentRequestedOrder();

            // act & assert
            mvc.perform(post("/api-admin/v1/orders/" + order.getId() + "/payment/fail")
                    .with(user("customer").roles("USER"))
                    .with(csrf()))
                .andExpect(status().isForbidden());
            assertThat(paymentAttemptRepository.findByOrderId(order.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentAttemptStatus.REQUESTED);
        }
    }

    @DisplayName("POST /api-admin/v1/orders/{orderId}/payment/reconcile")
    @Nested
    class ReconcilePayment {
        @DisplayName("PG에서 아직 거래를 찾지 못하면, 202와 관리자용 결제 정보(PG 식별자 등)를 반환한다.")
        @Test
        void returns202_withAdminPaymentFields() throws Exception {
            // arrange — 테스트 PG의 목록 조회 stub이 없어 조회 실패다: 상태를 바꾸지 않는다
            Order order = givenPaymentRequestedOrder();

            // act & assert
            mvc.perform(post("/api-admin/v1/orders/" + order.getId() + "/payment/reconcile")
                    .with(user("admin").roles("ADMIN"))
                    .with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.payment.status").value("REQUESTED"))
                .andExpect(jsonPath("$.data.payment.providerOrderId").value(String.format("LP-ORD-%010d", order.getId())))
                .andExpect(jsonPath("$.data.payment.sendStartedAt").exists())
                .andExpect(jsonPath("$.data.payment.emptyLookupCount").value(0));
        }

        @DisplayName("결제를 시작하지 않은 주문이면, 409를 반환한다.")
        @Test
        void returns409_whenPaymentNotStarted() throws Exception {
            Order order = givenOrder();

            mvc.perform(post("/api-admin/v1/orders/" + order.getId() + "/payment/reconcile")
                    .with(user("admin").roles("ADMIN"))
                    .with(csrf()))
                .andExpect(status().isConflict());
        }
    }
}
