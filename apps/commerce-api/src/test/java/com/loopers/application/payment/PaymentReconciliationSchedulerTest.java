package com.loopers.application.payment;

import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OrderItemCommand;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentAttemptModel;
import com.loopers.domain.payment.PaymentAttemptService;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.payment.PaymentAttemptJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.lang.reflect.Field;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
class PaymentReconciliationSchedulerTest {

    @Autowired PaymentReconciliationScheduler scheduler;
    @Autowired OrderFacade orderFacade;
    @Autowired OrderService orderService;
    @Autowired PaymentAttemptService paymentAttemptService;
    @Autowired PaymentAttemptJpaRepository paymentAttemptJpaRepository;
    @Autowired UserJpaRepository userJpaRepository;
    @Autowired BrandJpaRepository brandJpaRepository;
    @Autowired ProductJpaRepository productJpaRepository;
    @Autowired OrderJpaRepository orderJpaRepository;
    @Autowired DatabaseCleanUp databaseCleanUp;

    @MockBean PgPaymentPort pgPaymentPort;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    /**
     * 재고 예약 없이 PAYMENT_PENDING 상태의 주문과 PENDING 결제 시도를 만든다.
     * TX A 실행 후 JVM이 죽는 시나리오를 시뮬레이션한다.
     */
    private Long setupPendingAttemptScenario() throws Exception {
        UserModel user = userJpaRepository.save(new UserModel("대사 사용자"));
        BrandModel brand = brandJpaRepository.save(new BrandModel("브랜드"));
        ProductModel product = productJpaRepository.save(new ProductModel(brand.getId(), "상품", 10_000L, 5));

        Long orderId = orderFacade.createOrder(user.getId(), List.of(new OrderItemCommand(product.getId(), 1))).id();

        // TX A 완료 상태 시뮬레이션: 재고 예약 + 주문 PAYMENT_PENDING 전이
        ProductModel reloaded = productJpaRepository.findById(product.getId()).get();
        reloaded.reserve(1);
        productJpaRepository.save(reloaded);

        OrderModel order = orderJpaRepository.findById(orderId).get();
        order.startPayment();
        orderJpaRepository.save(order);

        String providerOrderId = "LP-ORD-" + String.format("%010d", orderId);
        PaymentAttemptModel attempt = paymentAttemptService.create(orderId, UUID.randomUUID().toString(), providerOrderId, 10_000L);

        Field sentAtField = PaymentAttemptModel.class.getDeclaredField("sentAt");
        sentAtField.setAccessible(true);
        sentAtField.set(attempt, ZonedDateTime.now().minusMinutes(15));
        paymentAttemptJpaRepository.save(attempt);

        return orderId;
    }

    @DisplayName("PENDING 결제 시도에 대해 PG에서 SUCCESS를 받으면 대사 후 CONFIRMED 상태가 된다.")
    @Test
    void reconcile_confirmsOrder_whenPgReturnsSuccess() throws Exception {
        Long orderId = setupPendingAttemptScenario();
        PaymentAttemptModel attempt = paymentAttemptService.getByOrderId(orderId);

        when(pgPaymentPort.getByProviderOrderId(anyString())).thenReturn(List.of(
            new PgPaymentResult("TX-REC", attempt.getProviderOrderId(), 10_000L, PgPaymentResult.PgStatus.SUCCESS)
        ));

        scheduler.reconcilePendingPayments();

        OrderModel order = orderJpaRepository.findById(orderId).get();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @DisplayName("PG 조회 결과가 없으면 대사를 건너뛰고 PAYMENT_PENDING 상태를 유지한다.")
    @Test
    void reconcile_skips_whenPgReturnsEmptyList() throws Exception {
        Long orderId = setupPendingAttemptScenario();

        when(pgPaymentPort.getByProviderOrderId(anyString())).thenReturn(List.of());

        scheduler.reconcilePendingPayments();

        OrderModel order = orderJpaRepository.findById(orderId).get();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
    }

    @DisplayName("대사가 두 번 호출되어도 멱등하게 처리된다.")
    @Test
    void reconcile_isIdempotent_whenCalledTwice() throws Exception {
        Long orderId = setupPendingAttemptScenario();
        PaymentAttemptModel attempt = paymentAttemptService.getByOrderId(orderId);

        when(pgPaymentPort.getByProviderOrderId(anyString())).thenReturn(List.of(
            new PgPaymentResult("TX-IDEM", attempt.getProviderOrderId(), 10_000L, PgPaymentResult.PgStatus.SUCCESS)
        ));

        scheduler.reconcilePendingPayments();
        scheduler.reconcilePendingPayments();

        OrderModel order = orderJpaRepository.findById(orderId).get();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }
}
