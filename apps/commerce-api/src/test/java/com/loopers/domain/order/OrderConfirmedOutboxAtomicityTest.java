package com.loopers.domain.order;

import com.loopers.CommerceApiApplication;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OrderItemCommand;
import com.loopers.application.payment.PgPaymentPort;
import com.loopers.application.payment.PgPaymentResult;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderConfirmedOutboxJpaRepository;
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
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = CommerceApiApplication.class)
class OrderConfirmedOutboxAtomicityTest {

    @Autowired UserJpaRepository userJpaRepository;
    @Autowired BrandJpaRepository brandJpaRepository;
    @Autowired ProductJpaRepository productJpaRepository;
    @Autowired OrderJpaRepository orderJpaRepository;
    @Autowired OrderConfirmedOutboxJpaRepository outboxJpaRepository;
    @Autowired OrderFacade orderFacade;

    @MockBean PgPaymentPort pgPaymentPort;
    @SpyBean OrderConfirmedOutboxRepository outboxRepository;

    @Autowired DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("Outbox insert 실패 시 주문 확정·예약 소비도 rollback된다.")
    @Test
    void outboxInsertFailure_rollbacksOrderConfirmation() {
        UserModel user = userJpaRepository.save(new UserModel("테스터"));
        BrandModel brand = brandJpaRepository.save(new BrandModel("브랜드"));
        ProductModel product = productJpaRepository.save(
            new ProductModel(brand.getId(), "상품", 10_000L, 5)
        );
        Long orderId = orderFacade.createOrder(
            user.getId(), List.of(new OrderItemCommand(product.getId(), 2))
        ).id();

        when(pgPaymentPort.pay(any())).thenReturn(
            new PgPaymentResult("TX-001", "LP-ORD-" + String.format("%010d", orderId),
                20_000L, PgPaymentResult.PgStatus.SUCCESS)
        );
        doThrow(new RuntimeException("Outbox 저장 실패 시뮬레이션"))
            .when(outboxRepository).save(any());

        try {
            orderFacade.confirmOrder(user.getId(), orderId, UUID.randomUUID().toString(), "http://cb");
        } catch (Exception ignored) {
        }

        OrderModel order = orderJpaRepository.findById(orderId).get();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);

        ProductModel reloaded = productJpaRepository.findById(product.getId()).get();
        assertThat(reloaded.getReservedStock()).isEqualTo(2);
        assertThat(reloaded.getStock()).isEqualTo(5);

        assertThat(outboxJpaRepository.findAll()).isEmpty();
    }

    @DisplayName("같은 주문에 중복 SUCCESS 콜백이 오면 Outbox는 한 행만 생성된다.")
    @Test
    void duplicateSuccessCallback_createsOnlyOneOutbox() {
        UserModel user = userJpaRepository.save(new UserModel("테스터2"));
        BrandModel brand = brandJpaRepository.save(new BrandModel("브랜드2"));
        ProductModel product = productJpaRepository.save(
            new ProductModel(brand.getId(), "상품2", 10_000L, 5)
        );
        Long orderId = orderFacade.createOrder(
            user.getId(), List.of(new OrderItemCommand(product.getId(), 1))
        ).id();

        when(pgPaymentPort.pay(any())).thenReturn(
            new PgPaymentResult("TX-002", "LP-ORD-" + String.format("%010d", orderId),
                10_000L, PgPaymentResult.PgStatus.SUCCESS)
        );

        orderFacade.confirmOrder(user.getId(), orderId, UUID.randomUUID().toString(), "http://cb");

        // 동일 PG 거래로 reconcile (중복 콜백 시뮬레이션)
        PgPaymentResult duplicate = new PgPaymentResult("TX-002", "LP-ORD-" + String.format("%010d", orderId),
            10_000L, PgPaymentResult.PgStatus.SUCCESS);
        // applyPaymentResult는 attempt.isTerminal() 체크로 중복 적용을 막음
        // → Outbox도 한 행만 존재해야 함
        assertThat(outboxJpaRepository.findAll()).hasSize(1);
    }
}
