package com.loopers.purchase.application;

import com.loopers.purchase.application.port.in.PurchaseActivityUseCase;
import com.loopers.purchase.application.port.in.PurchaseActivityUseCase.Result;
import com.loopers.purchase.domain.ConflictingOrderConfirmationException;
import com.loopers.purchase.domain.ConsumptionVerdict;
import com.loopers.purchase.domain.OrderConfirmedMessage;
import com.loopers.purchase.domain.ProductPurchaseActivity;
import com.loopers.support.StreamerIntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.loopers.support.KafkaTestSupport.orderConfirmedJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * W6 D-6·ADR-W6-05 멱등 consumer (C-6). 완료 기록과 품목을 한 TX로 저장하고, 같은 사실은 no-op, 충돌은 저장 없이 드러낸다.
 * 예: 주문 42 = 상품 7 × 2 · 상품 8 × 1 → 활동 2행. 같은 이벤트를 다시 받아도 2행.
 */
class PurchaseActivityServiceIntegrationTest extends StreamerIntegrationTestSupport {

    private static final String AT = "2026-10-28T14:03:12.123456+09:00";

    @Autowired
    private PurchaseActivityUseCase purchaseActivityUseCase;

    private static OrderConfirmedMessage order(String eventId, long orderId, long[][] items) {
        List<OrderConfirmedMessage.Item> lines = java.util.Arrays.stream(items)
            .map(item -> new OrderConfirmedMessage.Item(item[0], (int) item[1])).toList();
        return new OrderConfirmedMessage(eventId, "OrderConfirmed", 1, orderId, AT, lines);
    }

    private Result consume(String eventId, long orderId, long[][] items) {
        return purchaseActivityUseCase.consume(order(eventId, orderId, items), orderConfirmedJson(eventId, orderId, AT, items));
    }

    @DisplayName("처음 받으면, ")
    @Nested
    class FirstTime {

        @DisplayName("C-6 완료 기록 1행과 주문·상품별 활동 2행(7×2, 8×1)을 확정 시각 그대로 저장한다.")
        @Test
        void savesConsumptionAndActivities() {
            Result result = consume("evt-42", 42L, new long[][]{{8, 1}, {7, 2}});

            assertThat(result).isEqualTo(Result.SAVED);
            assertThat(consumedCount()).isEqualTo(1);
            assertThat(activityRepository.findByOrderIdOrderByProductId(42L))
                .extracting(ProductPurchaseActivity::getProductId, ProductPurchaseActivity::getQuantity, ProductPurchaseActivity::getEventId)
                .containsExactly(tuple(7L, 2, "evt-42"), tuple(8L, 1, "evt-42"));
            assertThat(activityRepository.findByOrderIdOrderByProductId(42L))
                .allSatisfy(row -> assertThat(row.getConfirmedAt().toInstant()).isEqualTo(ZonedDateTime.parse(AT).toInstant()));
        }

        @DisplayName("C-3 주문 42(7×2, 8×1)와 주문 43(7×3)이면 활동 3행, 상품 7의 구매 수량 합은 5다 — 랭킹 입력으로 쓸 수 있다.")
        @Test
        void sumsQuantityPerProductAcrossOrders() {
            consume("evt-42", 42L, new long[][]{{7, 2}, {8, 1}});
            consume("evt-43", 43L, new long[][]{{7, 3}});

            assertThat(activityCount()).isEqualTo(3);
            assertThat(jdbcTemplate.queryForObject("SELECT SUM(quantity) FROM product_purchase_activity WHERE product_id = 7", Long.class))
                .isEqualTo(5L);
        }
    }

    @DisplayName("이미 완료 기록이 있으면, ")
    @Nested
    class AlreadyConsumed {

        @DisplayName("C-6 같은 eventId · 같은 사실(items 순서만 다름)은 no-op — 활동은 여전히 2행이다.")
        @Test
        void sameFactIsNoOp() {
            consume("evt-42", 42L, new long[][]{{7, 2}, {8, 1}});

            Result second = consume("evt-42", 42L, new long[][]{{8, 1}, {7, 2}});

            assertThat(second).isEqualTo(Result.DUPLICATE);
            assertThat(consumedCount()).isEqualTo(1);
            assertThat(activityCount()).isEqualTo(2);
        }

        @DisplayName("C-6 같은 eventId · 다른 수량(7×3)은 충돌 — 저장하지 않고 두 원문과 함께 드러낸다.")
        @Test
        void sameEventDifferentFactIsConflict() {
            consume("evt-42", 42L, new long[][]{{7, 2}, {8, 1}});

            assertThatThrownBy(() -> consume("evt-42", 42L, new long[][]{{7, 3}, {8, 1}}))
                .isInstanceOf(ConflictingOrderConfirmationException.class)
                .hasMessageContaining("\"quantity\":2").hasMessageContaining("\"quantity\":3")
                .extracting(e -> ((ConflictingOrderConfirmationException) e).getVerdict())
                .isEqualTo(ConsumptionVerdict.CONFLICT_SAME_EVENT_DIFFERENT_FACT);
            assertThat(activityRepository.findByOrderIdOrderByProductId(42L))
                .extracting(ProductPurchaseActivity::getProductId, ProductPurchaseActivity::getQuantity)
                .containsExactly(tuple(7L, 2), tuple(8L, 1));
        }

        @DisplayName("C-6 같은 주문 · 다른 eventId는 사실이 같아도 충돌 — 구매가 두 배가 되지 않는다.")
        @Test
        void sameOrderDifferentEventIsConflict() {
            consume("evt-42", 42L, new long[][]{{7, 2}});

            assertThatThrownBy(() -> consume("evt-42-again", 42L, new long[][]{{7, 2}}))
                .isInstanceOf(ConflictingOrderConfirmationException.class)
                .extracting(e -> ((ConflictingOrderConfirmationException) e).getVerdict())
                .isEqualTo(ConsumptionVerdict.CONFLICT_SAME_ORDER_DIFFERENT_EVENT);
            assertThat(consumedCount()).isEqualTo(1);
            assertThat(activityCount()).isEqualTo(1);
        }
    }

    @DisplayName("C-6 같은 이벤트가 동시에 두 번 처리되면(rebalance 중 예전·새 consumer) 하나만 저장하고 다른 하나는 no-op이다 — 활동 2행.")
    @Test
    void concurrentDuplicatesSaveOnce() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        Callable<Result> task = () -> {
            start.await();
            return consume("evt-42", 42L, new long[][]{{7, 2}, {8, 1}});
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Result> first = pool.submit(task);
            Future<Result> second = pool.submit(task);
            start.countDown();

            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(Result.SAVED, Result.DUPLICATE);
        } finally {
            pool.shutdownNow();
        }
        assertThat(consumedCount()).isEqualTo(1);
        assertThat(activityCount()).isEqualTo(2);
    }

    @DisplayName("C-6 두 번째 품목 저장이 실패하면 완료 기록과 첫 품목까지 rollback하고, 완료 기록이 없으니 중복으로 바꾸지 않고 그대로 실패한다.")
    @Test
    void failureInSecondItemRollsBackEverything() {
        // arrange: (주문 42, 상품 8) 행이 이미 있어 두 번째 품목 INSERT가 유일 제약에 걸린다
        jdbcTemplate.update("INSERT INTO product_purchase_activity (order_id, product_id, quantity, confirmed_at, event_id, created_at, updated_at) "
            + "VALUES (42, 8, 9, NOW(6), 'orphan', NOW(6), NOW(6))");

        assertThatThrownBy(() -> consume("evt-42", 42L, new long[][]{{7, 2}, {8, 1}}))
            .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(consumedCount()).isZero();
        assertThat(activityRepository.findByOrderIdOrderByProductId(42L))
            .extracting(ProductPurchaseActivity::getProductId, ProductPurchaseActivity::getEventId)
            .containsExactly(tuple(8L, "orphan"));
    }
}
