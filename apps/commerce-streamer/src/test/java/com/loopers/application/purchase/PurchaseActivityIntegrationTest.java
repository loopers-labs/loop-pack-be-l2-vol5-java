package com.loopers.application.purchase;

import com.loopers.domain.purchase.OrderConfirmation;
import com.loopers.domain.purchase.OrderEventConflictException;
import com.loopers.domain.purchase.ProductPurchaseActivity;
import com.loopers.domain.purchase.PurchaseActivityRepository;
import com.loopers.infrastructure.purchase.ProcessedOrderConfirmationJpaRepository;
import com.loopers.infrastructure.purchase.ProductPurchaseActivityJpaRepository;
import com.loopers.testcontainers.KafkaBrokerFixture;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

/**
 * 구매 활동 저장의 정합성을 실제 MySQL로 확인한다 (W6 과제 3절). Kafka는 거치지 않고 handler를 직접 호출한다
 * (broker를 거친 전달은 KafkaDeliveryTest들이 확인한다). 주문 42가 상품 7을 2개·상품 8을 1개, 주문 43이 상품 7을 3개 산 장면을 쓴다.
 */
@SpringBootTest(properties = "order-event.listener-auto-startup=false")
class PurchaseActivityIntegrationTest {

    private static final Instant CONFIRMED_AT = Instant.parse("2026-09-07T01:00:00Z");

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KafkaBrokerFixture::bootstrapServers);
    }

    @Autowired
    private PurchaseActivityHandler handler;

    @Autowired
    private ProcessedOrderConfirmationJpaRepository processedRepository;

    @Autowired
    private ProductPurchaseActivityJpaRepository activityRepository;

    @MockitoSpyBean
    private PurchaseActivityRepository repository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private static OrderConfirmation order42() {
        return new OrderConfirmation("order-confirmed:v1:42", 42L, CONFIRMED_AT,
            List.of(new OrderConfirmation.Item(7L, 2), new OrderConfirmation.Item(8L, 1)));
    }

    private static OrderConfirmation order43() {
        return new OrderConfirmation("order-confirmed:v1:43", 43L, CONFIRMED_AT.plusSeconds(60),
            List.of(new OrderConfirmation.Item(7L, 3)));
    }

    @DisplayName("확정 주문을 소비하면, ")
    @Nested
    class WhenConsumed {

        @DisplayName("완료 기록 1건과 상품별 구매 활동이 수량·확정 시각 그대로 저장된다.")
        @Test
        void storesActivityPerProduct() {
            // act
            PurchaseActivityRecorder.Result result = handler.consume(order42());

            // assert
            assertThat(result).isEqualTo(PurchaseActivityRecorder.Result.RECORDED);
            assertThat(processedRepository.findByEventId("order-confirmed:v1:42")).isPresent();
            List<ProductPurchaseActivity> activities = activityRepository.findByOrderIdOrderByProductId(42L);
            assertThat(activities).extracting(ProductPurchaseActivity::getProductId).containsExactly(7L, 8L);
            assertThat(activities).extracting(ProductPurchaseActivity::getQuantity).containsExactly(2, 1);
            assertThat(activities).extracting(ProductPurchaseActivity::getConfirmedAt).containsOnly(CONFIRMED_AT);
        }

        @DisplayName("같은 이벤트를 다시 받아도 정상 no-op이라 활동은 여전히 2행이다. 다른 주문이 더해지면 총 3행, 상품 7의 수량 합은 5다.")
        @Test
        void redeliveryIsNoOp_andOtherOrderAddsRows() {
            // act
            handler.consume(order42());
            PurchaseActivityRecorder.Result again = handler.consume(order42());
            handler.consume(order43());

            // assert
            assertThat(again).isEqualTo(PurchaseActivityRecorder.Result.ALREADY_PROCESSED);
            assertThat(activityRepository.findByOrderIdOrderByProductId(42L)).hasSize(2);
            assertThat(activityRepository.count()).isEqualTo(3);
            assertThat(activityRepository.findByProductId(7L)).extracting(ProductPurchaseActivity::getQuantity)
                .containsExactlyInAnyOrder(2, 3);
            assertThat(activityRepository.findByProductId(7L).stream().mapToInt(ProductPurchaseActivity::getQuantity).sum()).isEqualTo(5);
            assertThat(processedRepository.count()).isEqualTo(2);
        }
    }

    @DisplayName("이미 처리한 사실과 충돌하면, ")
    @Nested
    class WhenConflicting {

        @DisplayName("같은 eventId에 다른 수량·다른 확정 시각이 와도 오류로 드러내고, 활동을 더하거나 바꾸지 않는다.")
        @Test
        void sameEventIdWithDifferentFact_isConflict() {
            // arrange
            handler.consume(order42());
            OrderConfirmation otherQuantity = new OrderConfirmation("order-confirmed:v1:42", 42L, CONFIRMED_AT,
                List.of(new OrderConfirmation.Item(7L, 5), new OrderConfirmation.Item(8L, 1)));
            OrderConfirmation otherTime = new OrderConfirmation("order-confirmed:v1:42", 42L, CONFIRMED_AT.plusSeconds(1),
                List.of(new OrderConfirmation.Item(7L, 2), new OrderConfirmation.Item(8L, 1)));

            // act & assert
            assertThatThrownBy(() -> handler.consume(otherQuantity)).isInstanceOf(OrderEventConflictException.class);
            assertThatThrownBy(() -> handler.consume(otherTime)).isInstanceOf(OrderEventConflictException.class);
            assertThat(activityRepository.findByOrderIdOrderByProductId(42L)).extracting(ProductPurchaseActivity::getQuantity)
                .containsExactly(2, 1);
            assertThat(activityRepository.count()).isEqualTo(2);
        }

        @DisplayName("같은 orderId에 다른 eventId가 오면 오류로 드러내고, 추가 구매를 만들지 않는다.")
        @Test
        void sameOrderIdWithDifferentEventId_isConflict() {
            // arrange
            handler.consume(order42());
            OrderConfirmation otherEvent = new OrderConfirmation("order-confirmed:v1:42-again", 42L, CONFIRMED_AT,
                List.of(new OrderConfirmation.Item(7L, 2), new OrderConfirmation.Item(8L, 1)));

            // act & assert
            assertThatThrownBy(() -> handler.consume(otherEvent)).isInstanceOf(OrderEventConflictException.class);
            assertThat(activityRepository.count()).isEqualTo(2);
            assertThat(processedRepository.count()).isEqualTo(1);
        }
    }

    @DisplayName("저장이 실패하거나 경쟁하면, ")
    @Nested
    class WhenStorageFailsOrRaces {

        @DisplayName("중간 품목 저장이 실패하면 완료 기록과 앞서 저장한 품목도 rollback되고, 다시 소비하면 처음부터 저장된다.")
        @Test
        void rollsBackEverything_whenMiddleItemFails() {
            // arrange: 첫 품목은 저장하고 두 번째 품목에서 실패한다
            doCallRealMethod().doThrow(new IllegalStateException("두 번째 품목 저장 실패(주입)"))
                .when(repository).saveActivity(any());

            // act
            assertThatThrownBy(() -> handler.consume(order42())).isInstanceOf(IllegalStateException.class);

            // assert
            assertThat(processedRepository.count()).as("완료 기록도 함께 rollback").isZero();
            assertThat(activityRepository.count()).as("앞서 저장한 품목도 함께 rollback").isZero();

            // act: 장애가 풀린 뒤 같은 이벤트가 다시 온다
            Mockito.reset(repository);
            PurchaseActivityRecorder.Result retried = handler.consume(order42());

            // assert
            assertThat(retried).isEqualTo(PurchaseActivityRecorder.Result.RECORDED);
            assertThat(activityRepository.count()).isEqualTo(2);
        }

        @DisplayName("같은 이벤트를 동시에 소비해도 유일 제약이 지켜져 구매 효과는 한 번이고, 한 건만 저장했고 나머지는 이미 처리로 끝난다.")
        @Test
        void concurrentConsumption_recordsOnce() throws Exception {
            // arrange
            int threads = 8;
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            List<Future<PurchaseActivityRecorder.Result>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<PurchaseActivityRecorder.Result> task = () -> {
                    ready.countDown();
                    go.await();
                    return handler.consume(order42());
                };
                futures.add(executor.submit(task));
            }

            // act
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<PurchaseActivityRecorder.Result> results = new ArrayList<>();
            for (Future<PurchaseActivityRecorder.Result> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            executor.shutdownNow();

            // assert
            assertThat(results.stream().filter(r -> r == PurchaseActivityRecorder.Result.RECORDED)).hasSize(1);
            assertThat(results.stream().filter(r -> r == PurchaseActivityRecorder.Result.ALREADY_PROCESSED)).hasSize(threads - 1);
            assertThat(processedRepository.count()).isEqualTo(1);
            assertThat(activityRepository.count()).isEqualTo(2);
        }
    }
}
