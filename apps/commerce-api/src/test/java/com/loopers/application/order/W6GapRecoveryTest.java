package com.loopers.application.order;

import com.loopers.CommerceApiApplication;
import com.loopers.application.payment.PgPaymentPort;
import com.loopers.application.payment.PgPaymentResult;
import com.loopers.domain.activity.ProductPurchaseActivityRepository;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderConfirmedOutboxJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
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
class W6GapRecoveryTest {

    @Autowired OrderFacade orderFacade;
    @Autowired OrderConfirmedOutboxRelay relay;
    @Autowired OrderConfirmedOutboxRecovery recovery;
    @Autowired OrderConfirmedOutboxJpaRepository outboxJpaRepository;
    @Autowired OrderJpaRepository orderJpaRepository;
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

    @DisplayName("W6 gap 상태(Outbox 없는 CONFIRMED 주문)에서 복구 후 relay → 구매 활동이 저장된다.")
    @Test
    void w6GapOrder_recoverAndRelay_purchaseActivitySaved() {
        UserModel user = userJpaRepository.save(new UserModel("복구 사용자"));
        BrandModel brand = brandJpaRepository.save(new BrandModel("복구 브랜드"));
        ProductModel product = productJpaRepository.save(
            new ProductModel(brand.getId(), "복구 상품", 10_000L, 5)
        );

        Long orderId = orderFacade.createOrder(
            user.getId(), List.of(new OrderItemCommand(product.getId(), 2))
        ).id();

        when(pgPaymentPort.pay(any())).thenReturn(
            new PgPaymentResult("TX-W6-001",
                "LP-ORD-" + String.format("%010d", orderId),
                20_000L, PgPaymentResult.PgStatus.SUCCESS)
        );

        // 주문 확정 (W7 흐름: Outbox 생성됨)
        orderFacade.confirmOrder(user.getId(), orderId, UUID.randomUUID().toString(), "http://cb");

        // W6 gap 시뮬레이션: Outbox를 삭제해 CONFIRMED 주문만 남긴다
        outboxJpaRepository.deleteAll();
        assertThat(outboxJpaRepository.findAll()).isEmpty();

        // 복구: 없는 Outbox를 재삽입
        OrderModel order = orderJpaRepository.findById(orderId).get();
        OrderConfirmedEvent event = OrderConfirmedEvent.from(order);
        recovery.recoverIfMissing(event);

        assertThat(outboxJpaRepository.findAll()).hasSize(1);

        // relay → Kafka 발행
        relay.relay();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(activityRepository.findAllByOrderId(orderId)).hasSize(1)
        );
    }

    @DisplayName("이미 Outbox가 존재하면 recoverIfMissing은 멱등하게 동작한다.")
    @Test
    void recoverIfMissing_idempotent_whenOutboxAlreadyExists() {
        UserModel user = userJpaRepository.save(new UserModel("멱등 사용자"));
        BrandModel brand = brandJpaRepository.save(new BrandModel("멱등 브랜드"));
        ProductModel product = productJpaRepository.save(
            new ProductModel(brand.getId(), "멱등 상품", 10_000L, 5)
        );

        Long orderId = orderFacade.createOrder(
            user.getId(), List.of(new OrderItemCommand(product.getId(), 1))
        ).id();

        when(pgPaymentPort.pay(any())).thenReturn(
            new PgPaymentResult("TX-W6-002",
                "LP-ORD-" + String.format("%010d", orderId),
                10_000L, PgPaymentResult.PgStatus.SUCCESS)
        );

        orderFacade.confirmOrder(user.getId(), orderId, UUID.randomUUID().toString(), "http://cb");

        assertThat(outboxJpaRepository.findAll()).hasSize(1);

        // 이미 Outbox 존재 → 중복 복구 호출도 오류 없이 1건 유지
        OrderModel order = orderJpaRepository.findById(orderId).get();
        OrderConfirmedEvent event = OrderConfirmedEvent.from(order);
        recovery.recoverIfMissing(event);

        assertThat(outboxJpaRepository.findAll()).hasSize(1);
    }
}
