package com.loopers.application.purchase.facade;

import com.loopers.domain.purchase.enums.PurchaseRecordResult;
import com.loopers.domain.purchase.repository.ProcessedOrderConfirmationRepository;
import com.loopers.domain.purchase.repository.ProductPurchaseActivityRepository;
import com.loopers.domain.purchase.vo.OrderConfirmation;
import com.loopers.support.error.RejectReason;
import com.loopers.support.error.RejectedEventException;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

/** 6주차 설계 4 의 저장 · 판정 장면. 실제 MySQL 의 유일 제약과 TX rollback 으로 확인함 */
@SpringBootTest
class PurchaseActivityIntegrationTest {

    private static final ZonedDateTime CONFIRMED_AT = ZonedDateTime.parse("2026-09-07T01:00:00.123456Z");

    @Autowired
    private PurchaseActivityFacade purchaseActivityFacade;

    @MockitoSpyBean
    private ProcessedOrderConfirmationRepository processedOrderConfirmationRepository;

    @MockitoSpyBean
    private ProductPurchaseActivityRepository productPurchaseActivityRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private static OrderConfirmation confirmation(long orderId, ZonedDateTime confirmedAt, OrderConfirmation.Item... items) {
        return new OrderConfirmation("order-confirmed:v1:" + orderId, orderId, confirmedAt, List.of(items));
    }

    private static OrderConfirmation.Item item(long productId, int quantity) {
        return new OrderConfirmation.Item(productId, quantity);
    }

    private long count(String sql) {
        return transactionTemplate.execute(status -> ((Number) entityManager.createNativeQuery(sql).getSingleResult()).longValue());
    }

    private long processedCount() {
        return count("select count(*) from processed_order_confirmation");
    }

    private long activityCount() {
        return count("select count(*) from product_purchase_activity");
    }

    @DisplayName("처음 받은 확정 사실이면, 처리 기록 하나와 품목마다 구매 활동 하나를 저장한다.")
    @Test
    void recordsNewConfirmation() {
        // act
        PurchaseRecordResult result = purchaseActivityFacade.record(confirmation(42L, CONFIRMED_AT, item(7L, 2), item(8L, 1)));

        // assert
        assertAll(
            () -> assertThat(result).isEqualTo(PurchaseRecordResult.RECORDED),
            () -> assertThat(processedCount()).isEqualTo(1),
            () -> assertThat(activityCount()).isEqualTo(2),
            () -> assertThat(count("select quantity from product_purchase_activity where order_id = 42 and product_id = 7")).isEqualTo(2)
        );
    }

    @DisplayName("다른 주문이 같은 상품을 사면 주문별로 따로 남아 상품별 합계가 된다. (설계 3장 예시: 주문 42 · 43)")
    @Test
    void keepsActivitiesPerOrder() {
        // act
        purchaseActivityFacade.record(confirmation(42L, CONFIRMED_AT, item(7L, 2), item(8L, 1)));
        purchaseActivityFacade.record(confirmation(42L, CONFIRMED_AT, item(7L, 2), item(8L, 1)));
        purchaseActivityFacade.record(confirmation(43L, CONFIRMED_AT.plusMinutes(1), item(7L, 3)));

        // assert
        assertAll(
            () -> assertThat(activityCount()).isEqualTo(3),
            () -> assertThat(count("select sum(quantity) from product_purchase_activity where product_id = 7")).isEqualTo(5)
        );
    }

    @DisplayName("이미 처리한 사실이 다시 오면, ")
    @Nested
    class Redelivery {
        @DisplayName("같은 내용이면 효과 없이 정상으로 끝난다. 품목 순서만 달라도 같은 사실이다.")
        @Test
        void isNoOp_whenSameFact() {
            // arrange
            purchaseActivityFacade.record(confirmation(42L, CONFIRMED_AT, item(7L, 2), item(8L, 1)));

            // act
            PurchaseRecordResult result = purchaseActivityFacade.record(confirmation(42L, CONFIRMED_AT, item(8L, 1), item(7L, 2)));

            // assert
            assertAll(
                () -> assertThat(result).isEqualTo(PurchaseRecordResult.ALREADY_RECORDED),
                () -> assertThat(processedCount()).isEqualTo(1),
                () -> assertThat(activityCount()).isEqualTo(2)
            );
        }

        @DisplayName("같은 eventId 에 다른 수량이나 확정 시각이면 CONFLICTING_CONTENT 로 거절하고 아무것도 바꾸지 않는다.")
        @Test
        void rejectsConflictingContent() {
            // arrange
            purchaseActivityFacade.record(confirmation(42L, CONFIRMED_AT, item(7L, 2)));

            // act
            RejectedEventException quantity = assertThrows(RejectedEventException.class,
                () -> purchaseActivityFacade.record(confirmation(42L, CONFIRMED_AT, item(7L, 3))));
            RejectedEventException time = assertThrows(RejectedEventException.class,
                () -> purchaseActivityFacade.record(confirmation(42L, CONFIRMED_AT.plusSeconds(1), item(7L, 2))));
            RejectedEventException product = assertThrows(RejectedEventException.class,
                () -> purchaseActivityFacade.record(confirmation(42L, CONFIRMED_AT, item(7L, 2), item(9L, 1))));

            // assert
            assertAll(
                () -> assertThat(List.of(quantity.getReason(), time.getReason(), product.getReason()))
                    .containsOnly(RejectReason.CONFLICTING_CONTENT),
                () -> assertThat(activityCount()).isEqualTo(1),
                () -> assertThat(count("select quantity from product_purchase_activity where order_id = 42")).isEqualTo(2)
            );
        }

        @DisplayName("같은 주문이 다른 eventId 로 오면 내용이 같아도 CONFLICTING_EVENT_ID 로 거절한다.")
        @Test
        void rejectsSameOrderWithOtherEventId() {
            // arrange
            purchaseActivityFacade.record(confirmation(42L, CONFIRMED_AT, item(7L, 2)));

            // act
            RejectedEventException result = assertThrows(RejectedEventException.class, () -> purchaseActivityFacade.record(
                new OrderConfirmation("order-confirmed:v2:42", 42L, CONFIRMED_AT, List.of(item(7L, 2)))));

            // assert
            assertAll(
                () -> assertThat(result.getReason()).isEqualTo(RejectReason.CONFLICTING_EVENT_ID),
                () -> assertThat(processedCount()).isEqualTo(1),
                () -> assertThat(activityCount()).isEqualTo(1)
            );
        }

        @DisplayName("같은 eventId 가 다른 주문으로 오면 CONFLICTING_ORDER 로 거절한다.")
        @Test
        void rejectsSameEventIdWithOtherOrder() {
            // arrange
            purchaseActivityFacade.record(confirmation(42L, CONFIRMED_AT, item(7L, 2)));

            // act
            RejectedEventException result = assertThrows(RejectedEventException.class, () -> purchaseActivityFacade.record(
                new OrderConfirmation("order-confirmed:v1:42", 43L, CONFIRMED_AT, List.of(item(7L, 2)))));

            // assert
            assertAll(
                () -> assertThat(result.getReason()).isEqualTo(RejectReason.CONFLICTING_ORDER),
                () -> assertThat(processedCount()).isEqualTo(1),
                () -> assertThat(count("select count(*) from product_purchase_activity where order_id = 43")).isZero()
            );
        }
    }

    @DisplayName("저장 중 실패하면, ")
    @Nested
    class Failure {
        @DisplayName("두 번째 품목에서 실패해도 처리 기록과 앞서 저장한 품목까지 모두 rollback 된다.")
        @Test
        void rollsBackEverything_whenItemSaveFails() {
            // arrange
            AtomicInteger saves = new AtomicInteger();
            doAnswer(invocation -> {
                if (saves.incrementAndGet() == 2) {
                    throw new IllegalStateException("주입한 품목 저장 실패");
                }
                return invocation.callRealMethod();
            }).when(productPurchaseActivityRepository).save(any());

            // act
            assertThrows(IllegalStateException.class,
                () -> purchaseActivityFacade.record(confirmation(42L, CONFIRMED_AT, item(7L, 2), item(8L, 1))));

            // assert
            assertAll(
                () -> assertThat(processedCount()).isZero(),
                () -> assertThat(activityCount()).isZero()
            );
        }

        @DisplayName("유일 제약 위반 뒤 다시 읽어도 처리 기록이 없으면, 중복으로 바꾸지 않고 원래 오류를 던진다. (D-85)")
        @Test
        void rethrows_whenNoRecordAfterViolation() {
            // arrange
            doThrow(new DataIntegrityViolationException("주입한 다른 무결성 오류"))
                .when(productPurchaseActivityRepository).save(any());

            // act
            assertThrows(DataIntegrityViolationException.class,
                () -> purchaseActivityFacade.record(confirmation(42L, CONFIRMED_AT, item(7L, 2))));

            // assert
            assertThat(processedCount()).isZero();
        }
    }

    @DisplayName("같은 사실을 동시에 처리하면, ")
    @Nested
    class Concurrency {
        @DisplayName("먼저 읽을 때 기록이 없었어도 유일 제약에 걸린 쪽은 새 TX 에서 다시 판정해 정상으로 끝낸다. (D-85)")
        @Test
        void rejudgesInNewTransaction_whenUniqueViolated() {
            // arrange: 다른 consumer 가 먼저 commit 했지만 이쪽은 그 전에 조회한 상황을 만듦
            OrderConfirmation fact = confirmation(42L, CONFIRMED_AT, item(7L, 2));
            purchaseActivityFacade.record(fact);
            doReturn(Optional.empty()).doCallRealMethod().when(processedOrderConfirmationRepository).findByEventId(anyString());
            doReturn(Optional.empty()).doCallRealMethod().when(processedOrderConfirmationRepository).findByOrderId(anyLong());

            // act
            PurchaseRecordResult result = purchaseActivityFacade.record(fact);

            // assert
            assertAll(
                () -> assertThat(result).isEqualTo(PurchaseRecordResult.ALREADY_RECORDED),
                () -> assertThat(processedCount()).isEqualTo(1),
                () -> assertThat(activityCount()).isEqualTo(1)
            );
        }

        @DisplayName("여러 스레드가 한꺼번에 처리해도 한 번만 저장되고 나머지는 효과 없이 끝난다.")
        @Test
        void recordsOnce_whenConcurrent() throws Exception {
            // arrange
            int threads = 8;
            OrderConfirmation fact = confirmation(42L, CONFIRMED_AT, item(7L, 2), item(8L, 1));
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<PurchaseRecordResult>> futures = new ArrayList<>();

            // act
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return purchaseActivityFacade.record(fact);
                }));
            }
            start.countDown();
            List<PurchaseRecordResult> results = new ArrayList<>();
            for (Future<PurchaseRecordResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            executor.shutdown();

            // assert
            assertAll(
                () -> assertThat(results).filteredOn(PurchaseRecordResult.RECORDED::equals).hasSize(1),
                () -> assertThat(results).filteredOn(PurchaseRecordResult.ALREADY_RECORDED::equals).hasSize(threads - 1),
                () -> assertThat(processedCount()).isEqualTo(1),
                () -> assertThat(activityCount()).isEqualTo(2)
            );
        }
    }
}
