package com.loopers.domain.order;

import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OrderInfo;
import com.loopers.application.order.OrderItemCommand;
import com.loopers.application.payment.PgPaymentPort;
import com.loopers.application.payment.PgPaymentResult;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.payment.PaymentAttemptService;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
class PaymentIntegrationTest {

    @Autowired UserJpaRepository userJpaRepository;
    @Autowired BrandJpaRepository brandJpaRepository;
    @Autowired ProductJpaRepository productJpaRepository;
    @Autowired OrderJpaRepository orderJpaRepository;
    @Autowired OrderFacade orderFacade;
    @Autowired PaymentAttemptService paymentAttemptService;

    @MockBean PgPaymentPort pgPaymentPort;

    @Autowired DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private UserModel createUser(String name) {
        return userJpaRepository.save(new UserModel(name));
    }

    private ProductModel createProduct(String productName, long price, int stock) {
        BrandModel brand = brandJpaRepository.save(new BrandModel("브랜드"));
        return productJpaRepository.save(new ProductModel(brand.getId(), productName, price, stock));
    }

    private Long createDraftOrder(Long userId, Long productId, int quantity) {
        return orderFacade.createOrder(userId, List.of(new OrderItemCommand(productId, quantity))).id();
    }

    @DisplayName("PG SUCCESS → 주문 CONFIRMED, 재고 차감, PaymentAttempt SUCCESS")
    @Test
    void fullFlow_pgSuccess() {
        UserModel user = createUser("성공 사용자");
        ProductModel product = createProduct("상품", 10_000L, 5);
        Long orderId = createDraftOrder(user.getId(), product.getId(), 2);

        when(pgPaymentPort.pay(any())).thenReturn(
            new PgPaymentResult("TX-001", "LP-ORD-" + String.format("%010d", orderId), 20_000L, PgPaymentResult.PgStatus.SUCCESS)
        );

        OrderInfo result = orderFacade.confirmOrder(user.getId(), orderId, UUID.randomUUID().toString(), "http://localhost/cb");

        assertThat(result.status()).isEqualTo("CONFIRMED");
        assertThat(result.paidAmount()).isEqualTo(20_000L);

        ProductModel reloaded = productJpaRepository.findById(product.getId()).get();
        assertThat(reloaded.getStock()).isEqualTo(3);
        assertThat(reloaded.getReservedStock()).isEqualTo(0);

        var attempt = paymentAttemptService.getByOrderId(orderId);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS);
        assertThat(attempt.getTransactionKey()).isEqualTo("TX-001");
    }

    @DisplayName("PG FAILED → 주문 PAYMENT_FAILED, 예약 반환, PaymentAttempt FAILED")
    @Test
    void fullFlow_pgFailed() {
        UserModel user = createUser("실패 사용자");
        ProductModel product = createProduct("상품", 10_000L, 5);
        Long orderId = createDraftOrder(user.getId(), product.getId(), 2);

        when(pgPaymentPort.pay(any())).thenReturn(
            new PgPaymentResult(null, "LP-ORD-" + String.format("%010d", orderId), 20_000L, PgPaymentResult.PgStatus.FAILED)
        );

        OrderInfo result = orderFacade.confirmOrder(user.getId(), orderId, UUID.randomUUID().toString(), "http://localhost/cb");

        assertThat(result.status()).isEqualTo("PAYMENT_FAILED");

        ProductModel reloaded = productJpaRepository.findById(product.getId()).get();
        assertThat(reloaded.getStock()).isEqualTo(5);
        assertThat(reloaded.getReservedStock()).isEqualTo(0);

        var attempt = paymentAttemptService.getByOrderId(orderId);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
    }

    @DisplayName("PG REJECTED → 주문 PAYMENT_FAILED, 예약 반환, PaymentAttempt REJECTED_NO_EFFECT")
    @Test
    void fullFlow_pgRejected() {
        UserModel user = createUser("거절 사용자");
        ProductModel product = createProduct("상품", 10_000L, 5);
        Long orderId = createDraftOrder(user.getId(), product.getId(), 1);

        when(pgPaymentPort.pay(any())).thenReturn(
            new PgPaymentResult(null, "LP-ORD-" + String.format("%010d", orderId), 10_000L, PgPaymentResult.PgStatus.REJECTED)
        );

        OrderInfo result = orderFacade.confirmOrder(user.getId(), orderId, UUID.randomUUID().toString(), "http://localhost/cb");

        assertThat(result.status()).isEqualTo("PAYMENT_FAILED");

        ProductModel reloaded = productJpaRepository.findById(product.getId()).get();
        assertThat(reloaded.getStock()).isEqualTo(5);
        assertThat(reloaded.getReservedStock()).isEqualTo(0);

        var attempt = paymentAttemptService.getByOrderId(orderId);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.REJECTED_NO_EFFECT);
    }

    @DisplayName("PG UNKNOWN → 주문 PAYMENT_PENDING 유지, 재고 예약 유지, PaymentAttempt UNKNOWN")
    @Test
    void fullFlow_pgUnknown() {
        UserModel user = createUser("미확정 사용자");
        ProductModel product = createProduct("상품", 10_000L, 5);
        Long orderId = createDraftOrder(user.getId(), product.getId(), 2);

        when(pgPaymentPort.pay(any())).thenReturn(
            new PgPaymentResult(null, "LP-ORD-" + String.format("%010d", orderId), 20_000L, PgPaymentResult.PgStatus.UNKNOWN)
        );

        OrderInfo result = orderFacade.confirmOrder(user.getId(), orderId, UUID.randomUUID().toString(), "http://localhost/cb");

        assertThat(result.status()).isEqualTo("PAYMENT_PENDING");

        ProductModel reloaded = productJpaRepository.findById(product.getId()).get();
        assertThat(reloaded.getReservedStock()).isEqualTo(2);

        var attempt = paymentAttemptService.getByOrderId(orderId);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
    }

    @DisplayName("재고 부족 시 TX A에서 예외 발생, 주문이 DRAFT로 유지된다.")
    @Test
    void initiatePayment_failsWhenStockInsufficient() {
        UserModel user = createUser("재고 부족 사용자");
        ProductModel product = createProduct("상품", 10_000L, 1);
        Long orderId = createDraftOrder(user.getId(), product.getId(), 2);

        assertThatThrownBy(() ->
            orderFacade.confirmOrder(user.getId(), orderId, UUID.randomUUID().toString(), "http://localhost/cb")
        ).isInstanceOf(Exception.class);

        OrderModel order = orderJpaRepository.findById(orderId).get();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);

        ProductModel reloaded = productJpaRepository.findById(product.getId()).get();
        assertThat(reloaded.getReservedStock()).isEqualTo(0);
    }
}
