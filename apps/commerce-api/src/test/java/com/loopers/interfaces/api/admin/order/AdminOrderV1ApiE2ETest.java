package com.loopers.interfaces.api.admin.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderLine;
import com.loopers.domain.payment.entity.Payment;
import com.loopers.domain.payment.enums.ProviderTransactionStatus;
import com.loopers.domain.payment.vo.PaymentLookupResult;
import com.loopers.domain.payment.vo.ProviderTransaction;
import com.loopers.domain.user.User;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.payment.persistence.PaymentJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.ZonedDateTime;
import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminOrderV1ApiE2ETest {

    private static final String ENDPOINT = "/api-admin/v1/orders";
    private static final RequestPostProcessor ADMIN = user("admin").roles("ADMIN");
    private static final RequestPostProcessor CUSTOMER = user("customer").roles("USER");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private OrderJpaRepository orderJpaRepository;

    @Autowired
    private PaymentJpaRepository paymentJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private User buyer;
    private User anotherBuyer;

    @BeforeEach
    void setUp() {
        buyer = userJpaRepository.save(new User());
        anotherBuyer = userJpaRepository.save(new User());
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Order saveOrder(User user, String productName) {
        return orderJpaRepository.save(Order.draft(user.getId(), List.of(new OrderLine(10L, productName, 1_000L, 2)), ZonedDateTime.now()));
    }

    @DisplayName("일반 사용자와 식별 없는 요청은 403 으로 거절한다.")
    @Test
    void rejectsNonAdmin() throws Exception {
        mvc.perform(get(ENDPOINT).with(CUSTOMER)).andExpect(status().isForbidden());
        mvc.perform(get(ENDPOINT)).andExpect(status().isForbidden());
    }

    @DisplayName("status 를 생략하면 모든 구매자의 모든 상태 주문을, 구매자 식별자와 함께 돌려준다.")
    @Test
    void returnsAllBuyersOrders() throws Exception {
        saveOrder(buyer, "상품 A");
        saveOrder(anotherBuyer, "상품 B");

        mvc.perform(get(ENDPOINT).with(ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalCount").value(2))
            .andExpect(jsonPath("$.data.items[0].userId").value(anotherBuyer.getId()))
            .andExpect(jsonPath("$.data.items[0].status").value("DRAFT"));
    }

    @DisplayName("userId 로 거르면 그 구매자의 주문만 돌려준다.")
    @Test
    void filtersByBuyer() throws Exception {
        saveOrder(buyer, "상품 A");
        saveOrder(anotherBuyer, "상품 B");

        mvc.perform(get(ENDPOINT).param("userId", String.valueOf(buyer.getId())).with(ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalCount").value(1))
            .andExpect(jsonPath("$.data.items[0].representativeProductName").value("상품 A"));
    }

    @DisplayName("없는 userId 로 거르면, 빈 목록이 아니라 404 와 USER_NOT_FOUND 를 돌려준다. (D-24)")
    @Test
    void returnsUserNotFound_whenFilterUserDoesNotExist() throws Exception {
        mvc.perform(get(ENDPOINT).param("userId", "999999").with(ADMIN))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.meta.errorCode").value("USER_NOT_FOUND"));
    }

    @DisplayName("status 로 거르면 그 상태만, 모르는 값이면 400 을 돌려준다.")
    @Test
    void filtersByStatus() throws Exception {
        saveOrder(buyer, "상품 A");

        mvc.perform(get(ENDPOINT).param("status", "CONFIRMED").with(ADMIN))
            .andExpect(jsonPath("$.data.totalCount").value(0));
        mvc.perform(get(ENDPOINT).param("status", "PAID").with(ADMIN))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.meta.errorCode").value("BAD_REQUEST"));
    }

    @DisplayName("상세는 구매자 식별자와 품목 전체를 돌려주고, 없는 주문이면 404 를 돌려준다.")
    @Test
    void returnsDetail() throws Exception {
        Order order = saveOrder(buyer, "상품 A");

        mvc.perform(get(ENDPOINT + "/" + order.getId()).with(ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.userId").value(buyer.getId()))
            .andExpect(jsonPath("$.data.items[0].productName").value("상품 A"))
            .andExpect(jsonPath("$.data.items[0].amount").value(2_000));
        mvc.perform(get(ENDPOINT + "/999999").with(ADMIN))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.meta.errorCode").value("ORDER_NOT_FOUND"));
    }

    /** 같은 주문의 거래가 2 건이라 충돌이 기록된 PG 결제 주문. 결제 상태는 REQUESTED 그대로임 */
    private Order saveConflictedPgOrder(User user) {
        Order order = Order.draft(user.getId(), List.of(new OrderLine(10L, "상품 A", 1_000L, 2)), ZonedDateTime.now());
        order.startPayment(ZonedDateTime.now());
        Order saved = orderJpaRepository.save(order);
        Payment payment = Payment.request(saved.getId(), "attempt-1", 2_000L);
        String providerOrderId = Payment.providerOrderIdOf(saved.getId());
        payment.apply(new PaymentLookupResult.Found(List.of(
            new ProviderTransaction("TX-1", providerOrderId, 2_000L, ProviderTransactionStatus.SUCCESS),
            new ProviderTransaction("TX-2", providerOrderId, 2_000L, ProviderTransactionStatus.SUCCESS))), ZonedDateTime.now());
        paymentJpaRepository.save(payment);
        return saved;
    }

    @DisplayName("목록 · 상세는 결제수단 · 결제 상태를 담고, 상세는 고객에게 숨기는 충돌 사유도 담는다. (5주차 설계 7장)")
    @Test
    void returnsPaymentFields_withConflictOnDetail() throws Exception {
        Order order = saveConflictedPgOrder(buyer);

        mvc.perform(get(ENDPOINT).param("status", "PAYMENT_PENDING").with(ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].paymentMethod").value("PG"))
            .andExpect(jsonPath("$.data.items[0].paymentStatus").value("REQUESTED"));
        mvc.perform(get(ENDPOINT + "/" + order.getId()).with(ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.paymentMethod").value("PG"))
            .andExpect(jsonPath("$.data.paymentStatus").value("REQUESTED"))
            .andExpect(jsonPath("$.data.paymentConflict").value("MULTIPLE_TRANSACTIONS"));
    }

    @DisplayName("결제 시도가 없는 주문이면, 결제 필드를 비워 둔다.")
    @Test
    void leavesPaymentFieldsEmpty_whenNoAttempt() throws Exception {
        Order order = saveOrder(buyer, "상품 A");

        mvc.perform(get(ENDPOINT + "/" + order.getId()).with(ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.paymentMethod").doesNotExist())
            .andExpect(jsonPath("$.data.paymentStatus").doesNotExist())
            .andExpect(jsonPath("$.data.paymentConflict").doesNotExist());
    }
}
