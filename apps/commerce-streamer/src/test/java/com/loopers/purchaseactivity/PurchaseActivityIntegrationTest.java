package com.loopers.purchaseactivity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import com.loopers.kafka.event.OrderConfirmed;
import com.loopers.purchaseactivity.application.PurchaseActivityHandler;
import com.loopers.purchaseactivity.domain.PurchaseActivity;
import com.loopers.purchaseactivity.domain.PurchaseActivityRepository;
import com.loopers.purchaseactivity.domain.ProcessedOrderConfirmationRepository;
import com.loopers.purchaseactivity.domain.ConfirmationConflictException;
import com.loopers.purchaseactivity.infrastructure.ProcessedOrderConfirmationJpaRepository;
import com.loopers.purchaseactivity.infrastructure.ProductPurchaseActivityEntity;
import com.loopers.purchaseactivity.infrastructure.ProductPurchaseActivityJpaRepository;
import testsupport.purchaseactivity.PurchaseActivityDatabaseTestConfiguration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@SpringBootTest(classes = PurchaseActivityDatabaseTestConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.config.location=classpath:application-purchase-test.yml")
class PurchaseActivityIntegrationTest {
    private static final Instant CONFIRMED_AT = Instant.parse("2026-09-07T01:00:00.123456Z");

    @Autowired private PurchaseActivityHandler handler;
    @Autowired private ProductPurchaseActivityJpaRepository activities;
    @Autowired private ProcessedOrderConfirmationJpaRepository confirmations;
    @MockitoSpyBean private PurchaseActivityRepository activityStore;
    @MockitoSpyBean private ProcessedOrderConfirmationRepository confirmationStore;

    @AfterEach
    void cleanDatabase() {
        activities.deleteAllInBatch();
        confirmations.deleteAllInBatch();
    }

    @Test
    void 주문_확정_이벤트를_소비하면_완료_기록과_모든_구매_품목을_저장한다() {
        // Arrange
        var event = OrderConfirmed.confirmed(42, CONFIRMED_AT, List.of(
                new OrderConfirmed.Item(7, 2), new OrderConfirmed.Item(8, 1)));
        assertThat(confirmations.count()).isZero();
        assertThat(activities.count()).isZero();

        // Act
        handler.consume(event);

        // Assert
        assertThat(confirmations.findByEventId(event.eventId())).isPresent();
        assertThat(activities.findByOrderIdOrderByProductId(42))
                .extracting(ProductPurchaseActivityEntity::toDomain)
                .containsExactly(new PurchaseActivity(42, 7, 2, CONFIRMED_AT),
                        new PurchaseActivity(42, 8, 1, CONFIRMED_AT));
    }

    @Test
    void 같은_사실을_다시_소비해도_구매_수량이_늘지_않는다() {
        // Arrange
        var event = confirmation(42);
        handler.consume(event);
        var before = activities.findByOrderIdOrderByProductId(42).stream()
                .map(ProductPurchaseActivityEntity::toDomain).toList();
        var sameFact = OrderConfirmed.confirmed(42, CONFIRMED_AT, List.of(
                new OrderConfirmed.Item(8, 1), new OrderConfirmed.Item(7, 1), new OrderConfirmed.Item(7, 1)));

        // Act
        handler.consume(sameFact);

        // Assert
        assertThat(confirmations.count()).isEqualTo(1);
        assertThat(activities.findByOrderIdOrderByProductId(42))
                .extracting(ProductPurchaseActivityEntity::toDomain).containsExactlyElementsOf(before);
    }

    @Test
    void 다른_주문에서_같은_상품을_구매하면_별도_구매_활동을_저장한다() {
        // Arrange
        handler.consume(confirmation(42));
        assertThat(activities.count()).isEqualTo(2);
        assertThat(activities.findByOrderIdOrderByProductId(42))
                .extracting(ProductPurchaseActivityEntity::toDomain)
                .containsExactly(new PurchaseActivity(42, 7, 2, CONFIRMED_AT),
                        new PurchaseActivity(42, 8, 1, CONFIRMED_AT));
        var anotherConfirmedAt = CONFIRMED_AT.plusSeconds(60);
        var anotherOrder = OrderConfirmed.confirmed(43, anotherConfirmedAt, List.of(new OrderConfirmed.Item(7, 3)));

        // Act
        handler.consume(anotherOrder);

        // Assert
        assertThat(confirmations.count()).isEqualTo(2);
        assertThat(activities.count()).isEqualTo(3);
        assertThat(activities.findByOrderIdOrderByProductId(42))
                .extracting(ProductPurchaseActivityEntity::toDomain)
                .containsExactly(new PurchaseActivity(42, 7, 2, CONFIRMED_AT),
                        new PurchaseActivity(42, 8, 1, CONFIRMED_AT));
        assertThat(activities.findByOrderIdOrderByProductId(43))
                .extracting(ProductPurchaseActivityEntity::toDomain)
                .containsExactly(new PurchaseActivity(43, 7, 3, anotherConfirmedAt));
        assertThat(activities.findAll().stream().map(ProductPurchaseActivityEntity::toDomain)
                .filter(activity -> activity.productId() == 7).mapToInt(PurchaseActivity::quantity).sum()).isEqualTo(5);
    }

    @ParameterizedTest
    @MethodSource("conflictingConfirmations")
    void 같은_이벤트나_주문_ID의_다른_사실은_추가_구매_없이_충돌로_드러낸다(OrderConfirmed conflicting) {
        // Arrange
        var original = confirmation(42);
        handler.consume(original);
        var before = activities.findAll().stream().map(ProductPurchaseActivityEntity::toDomain).toList();

        // Act + Assert
        assertThatThrownBy(() -> handler.consume(conflicting)).isInstanceOf(ConfirmationConflictException.class);
        assertThat(confirmations.count()).isEqualTo(1);
        assertThat(activities.findAll()).extracting(ProductPurchaseActivityEntity::toDomain)
                .containsExactlyInAnyOrderElementsOf(before);
    }

    @Test
    void 두번째_품목_저장이_실패하면_완료_기록과_첫번째_품목도_롤백한다() {
        // Arrange
        var failure = new DataAccessResourceFailureException("두번째 품목 DB 저장 실패");
        doThrow(failure).when(activityStore).save(argThat(activity -> activity.productId() == 8));
        assertThat(confirmations.count()).isZero();
        assertThat(activities.count()).isZero();

        // Act + Assert
        assertThatThrownBy(() -> handler.consume(confirmation(42))).isSameAs(failure);
        assertThat(confirmations.count()).isZero();
        assertThat(activities.count()).isZero();
    }

    @Test
    void 중복과_무관한_DB_무결성_오류를_정상_중복으로_바꾸지_않는다() {
        // Arrange
        var failure = new DataIntegrityViolationException("구매 품목의 별도 제약 위반");
        doThrow(failure).when(activityStore).save(any());

        // Act + Assert
        assertThatThrownBy(() -> handler.consume(confirmation(42))).isSameAs(failure);
        assertThat(confirmations.count()).isZero();
        assertThat(activities.count()).isZero();
    }

    @Test
    void 동시에_같은_이벤트를_저장해도_유일_제약으로_구매_활동은_한번만_남는다() throws Exception {
        // Arrange
        var event = confirmation(42);
        var bothReadyToInsert = new CountDownLatch(2);
        var startInsert = new CountDownLatch(1);
        doAnswer(invocation -> {
            bothReadyToInsert.countDown();
            if (!startInsert.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent insert was not released");
            }
            return invocation.callRealMethod();
        }).when(confirmationStore).save(any());

        // Act
        try (var consumers = Executors.newFixedThreadPool(2)) {
            var first = consumers.submit(() -> handler.consume(event));
            var second = consumers.submit(() -> handler.consume(event));
            try {
                assertThat(bothReadyToInsert.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                startInsert.countDown();
            }
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }

        // Assert
        assertThat(confirmations.count()).isEqualTo(1);
        assertThat(activities.count()).isEqualTo(2);
        assertThat(activities.findByOrderIdOrderByProductId(42))
                .extracting(ProductPurchaseActivityEntity::toDomain)
                .containsExactly(new PurchaseActivity(42, 7, 2, CONFIRMED_AT),
                        new PurchaseActivity(42, 8, 1, CONFIRMED_AT));
    }

    private static OrderConfirmed confirmation(long orderId) {
        return OrderConfirmed.confirmed(orderId, CONFIRMED_AT, List.of(
                new OrderConfirmed.Item(7, 2), new OrderConfirmed.Item(8, 1)));
    }

    private static Stream<OrderConfirmed> conflictingConfirmations() {
        return Stream.of(
                new OrderConfirmed("OrderConfirmed", 1, "order-confirmed:v1:42", 43,
                        CONFIRMED_AT, confirmation(42).items()),
                OrderConfirmed.confirmed(42, CONFIRMED_AT, List.of(new OrderConfirmed.Item(7, 99))),
                OrderConfirmed.confirmed(42, CONFIRMED_AT.plusSeconds(1), confirmation(42).items()),
                new OrderConfirmed("OrderConfirmed", 1, "another-event-id", 42,
                        CONFIRMED_AT, confirmation(42).items()));
    }
}
