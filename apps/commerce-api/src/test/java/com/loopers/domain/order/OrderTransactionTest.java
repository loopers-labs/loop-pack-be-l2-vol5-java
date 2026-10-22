package com.loopers.domain.order;

import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OrderInfo;
import com.loopers.application.order.OrderItemCommand;
import com.loopers.application.payment.PgPaymentPort;
import com.loopers.application.payment.PgPaymentResult;
import com.loopers.domain.brand.BrandModel;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
class OrderTransactionTest {

    @Autowired UserJpaRepository userJpaRepository;
    @Autowired BrandJpaRepository brandJpaRepository;
    @Autowired ProductJpaRepository productJpaRepository;
    @Autowired OrderJpaRepository orderJpaRepository;

    @Autowired OrderFacade orderFacade;

    @MockBean PgPaymentPort pgPaymentPort;

    @Autowired DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("PG가 재고 예약 후 FAILED를 반환하면 예약이 반환되고 주문이 PAYMENT_FAILED가 된다.")
    @Test
    void rollsBack_whenPgReturnsFailed() {
        UserModel user = userJpaRepository.save(new UserModel("테스터"));
        BrandModel brand = brandJpaRepository.save(new BrandModel("브랜드A"));
        ProductModel product = productJpaRepository.save(
            new ProductModel(brand.getId(), "상품A", 10_000L, 10)
        );

        when(pgPaymentPort.pay(any())).thenReturn(
            new PgPaymentResult(null, "LP-ORD-0000000001", 10_000L, PgPaymentResult.PgStatus.FAILED)
        );

        OrderInfo orderInfo = orderFacade.createOrder(
            user.getId(),
            List.of(new OrderItemCommand(product.getId(), 1))
        );
        Long orderId = orderInfo.id();

        orderFacade.confirmOrder(user.getId(), orderId, UUID.randomUUID().toString(), "http://localhost/callback");

        ProductModel reloadedProduct = productJpaRepository.findById(product.getId()).get();
        assertThat(reloadedProduct.getReservedStock()).isEqualTo(0);
        assertThat(reloadedProduct.getStock()).isEqualTo(10);

        OrderModel reloadedOrder = orderJpaRepository.findById(orderId).get();
        assertThat(reloadedOrder.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
    }

    @DisplayName("PG가 성공을 반환하면 예약이 소비되고 주문이 CONFIRMED가 된다.")
    @Test
    void confirms_whenPgReturnsSuccess() {
        UserModel user = userJpaRepository.save(new UserModel("테스터"));
        BrandModel brand = brandJpaRepository.save(new BrandModel("브랜드B"));
        ProductModel product = productJpaRepository.save(
            new ProductModel(brand.getId(), "상품B", 10_000L, 10)
        );

        when(pgPaymentPort.pay(any())).thenReturn(
            new PgPaymentResult("TX-001", "LP-ORD-0000000001", 10_000L, PgPaymentResult.PgStatus.SUCCESS)
        );

        OrderInfo orderInfo = orderFacade.createOrder(
            user.getId(),
            List.of(new OrderItemCommand(product.getId(), 1))
        );
        Long orderId = orderInfo.id();

        orderFacade.confirmOrder(user.getId(), orderId, UUID.randomUUID().toString(), "http://localhost/callback");

        ProductModel reloadedProduct = productJpaRepository.findById(product.getId()).get();
        assertThat(reloadedProduct.getReservedStock()).isEqualTo(0);
        assertThat(reloadedProduct.getStock()).isEqualTo(9);

        OrderModel reloadedOrder = orderJpaRepository.findById(orderId).get();
        assertThat(reloadedOrder.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }
}
