package com.loopers.application.order;

import com.loopers.CommerceApiApplication;
import com.loopers.application.payment.PgPaymentPort;
import com.loopers.application.payment.PgPaymentResult;
import com.loopers.domain.activity.ProductPurchaseActivity;
import com.loopers.domain.activity.ProductPurchaseActivityRepository;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderConfirmedOutboxRepository;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderConfirmedOutboxJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.test.kafka.KafkaTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = CommerceApiApplication.class)
@Import(KafkaTestContainersConfig.class)
class OrderConfirmedE2ETest {

    @Autowired OrderFacade orderFacade;
    @Autowired OrderConfirmedOutboxRelay relay;
    @Autowired OrderConfirmedOutboxRepository outboxRepository;
    @Autowired OrderConfirmedOutboxJpaRepository outboxJpaRepository;
    @Autowired ProductPurchaseActivityRepository activityRepository;

    @MockBean PgPaymentPort pgPaymentPort;

    @Autowired UserJpaRepository userJpaRepository;
    @Autowired BrandJpaRepository brandJpaRepository;
    @Autowired ProductJpaRepository productJpaRepository;
    @Autowired DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("주문 확정 → Outbox → relay → Kafka → consumer → 구매 활동이 저장된다.")
    @Test
    void confirmOrder_outboxRelay_purchaseActivitySaved() {
        UserModel user = userJpaRepository.save(new UserModel("E2E 사용자"));
        BrandModel brand = brandJpaRepository.save(new BrandModel("E2E 브랜드"));
        ProductModel product = productJpaRepository.save(
            new ProductModel(brand.getId(), "E2E 상품", 10_000L, 5)
        );

        Long orderId = orderFacade.createOrder(
            user.getId(), List.of(new OrderItemCommand(product.getId(), 2))
        ).id();

        when(pgPaymentPort.pay(any())).thenReturn(
            new PgPaymentResult("TX-E2E-001",
                "LP-ORD-" + String.format("%010d", orderId),
                20_000L, PgPaymentResult.PgStatus.SUCCESS)
        );

        orderFacade.confirmOrder(user.getId(), orderId, UUID.randomUUID().toString(), "http://cb");

        assertThat(outboxJpaRepository.findAll()).hasSize(1);

        relay.relay();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<ProductPurchaseActivity> activities = activityRepository.findAllByOrderId(orderId);
            assertThat(activities).hasSize(1);
            assertThat(activities.get(0).getProductId()).isEqualTo(product.getId());
            assertThat(activities.get(0).getQuantity()).isEqualTo(2);
        });
    }
}
