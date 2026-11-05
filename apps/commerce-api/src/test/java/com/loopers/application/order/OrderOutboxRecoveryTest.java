package com.loopers.application.order;

import com.loopers.application.user.UserRegistrationService;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderConfirmed;
import com.loopers.domain.order.OrderOutbox;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.order.OrderOutboxJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.support.payment.FakePaymentServer;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 복구 규칙만 확인한다. W6 코드로 만든 실제 누락의 복구는 W6OutboxRecoveryExperimentTest가 검증한다. */
@SpringBootTest
class OrderOutboxRecoveryTest {
    private static final FakePaymentServer PG = startPg();

    @DynamicPropertySource
    static void pgUrl(DynamicPropertyRegistry registry) {
        registry.add("commerce.payment.pg.base-url", PG::baseUrl);
    }

    @Autowired private UserRegistrationService registration;
    @Autowired private OrderFacade orders;
    @Autowired private OrderOutboxRecovery recovery;
    @Autowired private BrandJpaRepository brands;
    @Autowired private ProductJpaRepository products;
    @Autowired private OrderJpaRepository orderRepository;
    @Autowired private OrderOutboxJpaRepository outbox;
    @Autowired private DatabaseCleanUp cleanUp;

    private Long userId;
    private Long orderId;

    @BeforeEach
    void setUp() {
        PG.reset();
        userId = registration.register().getId();
        Long brandId = brands.save(new BrandModel("복구 테스트 브랜드", null)).getId();
        Long productId = products.save(new ProductModel(brandId, "양말", 1_000L, 10)).getId();
        orderId = orders.create(userId, List.of(new OrderFacade.OrderLine(productId, 2))).id();
    }

    @AfterEach
    void tearDown() {
        cleanUp.truncateAllTables();
    }

    @AfterAll
    static void stopPg() throws InterruptedException {
        PG.close();
    }

    @Test
    void addsRowFromStoredFactsWithSameIdentityOnlyOnce() {
        confirm();
        String recordedByConfirmation = onlyOutbox().getPayload();
        outbox.deleteAll();

        assertThat(recovery.recover(orderId)).isTrue();
        assertThat(recovery.recover(orderId)).isFalse();

        OrderOutbox recovered = onlyOutbox();
        assertThat(recovered.getEventId()).isEqualTo("order-confirmed:v1:" + orderId);
        assertThat(recovered.getPayload()).isEqualTo(recordedByConfirmation);
    }

    @Test
    void conflictingStoredRowIsReportedAndLeftUnchanged() {
        confirm();
        var order = orderRepository.findById(orderId).orElseThrow();
        outbox.deleteAll();
        outbox.save(OrderOutbox.pending(new OrderConfirmed(orderId, Instant.parse("2026-01-01T00:00:00Z"),
            List.of(new OrderConfirmed.Item(99L, 1))), "OrderConfirmed", 1, "{\"different\":true}"));

        assertThatThrownBy(() -> recovery.recover(orderId)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("order-confirmed:v1:" + orderId);

        assertThat(onlyOutbox().getPayload()).isEqualTo("{\"different\":true}");
        assertThat(orderRepository.findById(orderId).orElseThrow().getConfirmedAt()).isEqualTo(order.getConfirmedAt());
    }

    @Test
    void unconfirmedOrderIsRejectedWithoutGuessing() {
        assertThatThrownBy(() -> recovery.recover(orderId)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("orderId=" + orderId);

        assertThat(outbox.count()).isZero();
    }

    private void confirm() {
        orders.confirm(userId, orderId, new OrderFacade.PaymentCommand("recovery-test", "TEST", "0000-0000-0000-0000"));
        PG.changeStatus("TEST-TX-1", "SUCCESS");
        orders.reconcile(userId, orderId);
    }

    private OrderOutbox onlyOutbox() {
        assertThat(outbox.count()).isEqualTo(1);
        return outbox.findAll().getFirst();
    }

    private static FakePaymentServer startPg() {
        try {
            return new FakePaymentServer();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
