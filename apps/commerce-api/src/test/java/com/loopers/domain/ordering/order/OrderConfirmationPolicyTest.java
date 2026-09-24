package com.loopers.domain.ordering.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.domain.mall.product.Product;
import com.loopers.domain.pay.wallet.PointBillType;
import com.loopers.domain.pay.wallet.Wallet;
import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class OrderConfirmationPolicyTest {

    @DisplayName("주문 확정")
    @Nested
    class Confirm {
        @DisplayName("재고·잔액이 충분하면 확정하고 재고·잔액을 차감하며 사용 기록을 반환한다")
        @Test
        void confirmsOrder_whenStockAndBalanceAreSufficient() {
            Order order = draftOrder(1L, List.of(
                OrderItem.restore(10L, "상품A", 1_000L, 2, 2_000L),
                OrderItem.restore(20L, "상품B", 500L, 1, 500L)
            ), 2_500L);
            Product productA = product(10L, 5);
            Product productB = product(20L, 5);
            Wallet wallet = Wallet.restore(1L, 10_000L);

            OrderConfirmation result = OrderConfirmationPolicy.confirm(
                order, Map.of(10L, productA, 20L, productB), wallet);

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(productA.getStock()).isEqualTo(3);
            assertThat(productB.getStock()).isEqualTo(4);
            assertThat(wallet.getBalance()).isEqualTo(7_500L);
            assertThat(result.pointBill().getUserId()).isEqualTo(1L);
            assertThat(result.pointBill().getType()).isEqualTo(PointBillType.USE);
            assertThat(result.pointBill().getOrderId()).isEqualTo(1L);
            assertThat(result.pointBill().getAmount()).isEqualTo(2_500L);
        }

        @DisplayName("동일 상품 품목은 총수량으로 합산해 한 번만 차감한다")
        @Test
        void aggregatesDuplicateProductItems_beforeDecreasingOnce() {
            Order order = draftOrder(1L, List.of(
                OrderItem.restore(10L, "상품A", 1_000L, 2, 2_000L),
                OrderItem.restore(10L, "상품A", 1_000L, 1, 1_000L)
            ), 3_000L);
            Product product = product(10L, 5);
            Wallet wallet = Wallet.restore(1L, 10_000L);

            OrderConfirmationPolicy.confirm(order, Map.of(10L, product), wallet);

            assertThat(product.getStock()).isEqualTo(2);
        }

        @DisplayName("합산 수량이 재고를 초과하면 개별 수량은 충분해도 거절하고 재고를 유지한다")
        @Test
        void rejectsAggregatedQuantity_exceedingStock_andKeepsStock() {
            Order order = draftOrder(1L, List.of(
                OrderItem.restore(10L, "상품A", 1_000L, 2, 2_000L),
                OrderItem.restore(10L, "상품A", 1_000L, 2, 2_000L)
            ), 4_000L);
            Product product = product(10L, 3);
            Wallet wallet = Wallet.restore(1L, 10_000L);

            assertThatThrownBy(() -> OrderConfirmationPolicy.confirm(order, Map.of(10L, product), wallet))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.INSUFFICIENT_STOCK);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
            assertThat(product.getStock()).isEqualTo(3);
        }

        @DisplayName("동일 상품의 합산 수량이 계산 범위를 초과하면 거절하고 재고를 유지한다")
        @Test
        void rejectsQuantityOverflow_andKeepsStock() {
            Order order = draftOrder(1L, List.of(
                OrderItem.restore(10L, "상품A", 1L, Integer.MAX_VALUE, Integer.MAX_VALUE),
                OrderItem.restore(10L, "상품A", 1L, 1, 1L)
            ), Integer.MAX_VALUE + 1L);
            Product product = product(10L, 5);
            Wallet wallet = Wallet.restore(1L, 10_000L);

            assertThatThrownBy(() -> OrderConfirmationPolicy.confirm(order, Map.of(10L, product), wallet))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.CALCULATION_OVERFLOW);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
            assertThat(product.getStock()).isEqualTo(5);
        }

        @DisplayName("이미 확정된 주문은 상태 오류로 거절하고 재고·잔액을 유지한다")
        @Test
        void rejectsReconfirm_andKeepsState() {
            Order order = confirmedOrder(1L, List.of(OrderItem.restore(10L, "상품A", 1_000L, 1, 1_000L)), 1_000L);
            Product product = product(10L, 5);
            Wallet wallet = Wallet.restore(1L, 10_000L);

            assertThatThrownBy(() -> OrderConfirmationPolicy.confirm(order, Map.of(10L, product), wallet))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.ORDER_ALREADY_CONFIRMED);
            assertThat(product.getStock()).isEqualTo(5);
            assertThat(wallet.getBalance()).isEqualTo(10_000L);
        }

        @DisplayName("품목 상품이 삭제됐으면 거절하고 재고·잔액을 유지한다")
        @Test
        void rejectsDeletedProduct_andKeepsState() {
            Order order = draftOrder(1L, List.of(OrderItem.restore(10L, "상품A", 1_000L, 1, 1_000L)), 1_000L);
            Product product = product(10L, 5);
            product.delete();
            Wallet wallet = Wallet.restore(1L, 10_000L);

            assertThatThrownBy(() -> OrderConfirmationPolicy.confirm(order, Map.of(10L, product), wallet))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.DELETED_PRODUCT);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
            assertThat(wallet.getBalance()).isEqualTo(10_000L);
        }

        @DisplayName("재고가 요청 수량보다 1 부족하면 거절하고 재고·잔액을 유지한다")
        @Test
        void rejectsStockShortByOne_andKeepsState() {
            Order order = draftOrder(1L, List.of(OrderItem.restore(10L, "상품A", 1_000L, 5, 5_000L)), 5_000L);
            Product product = product(10L, 4);
            Wallet wallet = Wallet.restore(1L, 10_000L);

            assertThatThrownBy(() -> OrderConfirmationPolicy.confirm(order, Map.of(10L, product), wallet))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.INSUFFICIENT_STOCK);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
            assertThat(product.getStock()).isEqualTo(4);
            assertThat(wallet.getBalance()).isEqualTo(10_000L);
        }

        @DisplayName("뒤쪽 품목이 실패하면 앞쪽 품목의 재고도 차감되지 않는다")
        @Test
        void rejectsWhenLaterItemFails_keepingEarlierItemStockUnchanged() {
            Order order = draftOrder(1L, List.of(
                OrderItem.restore(10L, "상품A", 1_000L, 1, 1_000L),
                OrderItem.restore(20L, "상품B", 1_000L, 5, 5_000L)
            ), 6_000L);
            Product productA = product(10L, 5);
            Product productB = product(20L, 4);
            Wallet wallet = Wallet.restore(1L, 10_000L);

            assertThatThrownBy(() -> OrderConfirmationPolicy.confirm(
                order, Map.of(10L, productA, 20L, productB), wallet))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.INSUFFICIENT_STOCK);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
            assertThat(productA.getStock()).isEqualTo(5);
            assertThat(productB.getStock()).isEqualTo(4);
            assertThat(wallet.getBalance()).isEqualTo(10_000L);
        }

        @DisplayName("잔액이 결제액보다 1 부족하면 거절하고 재고·잔액을 유지한다")
        @Test
        void rejectsInsufficientBalance_andKeepsState() {
            Order order = draftOrder(1L, List.of(OrderItem.restore(10L, "상품A", 1_000L, 2, 2_000L)), 2_000L);
            Product product = product(10L, 5);
            Wallet wallet = Wallet.restore(1L, 1_999L);

            assertThatThrownBy(() -> OrderConfirmationPolicy.confirm(order, Map.of(10L, product), wallet))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.INSUFFICIENT_POINT);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
            assertThat(product.getStock()).isEqualTo(5);
            assertThat(wallet.getBalance()).isEqualTo(1_999L);
        }
    }

    private Order draftOrder(long id, List<OrderItem> items, long totalAmount) {
        return Order.restore(id, 1L, OrderStatus.DRAFT, items, totalAmount, Instant.now());
    }

    private Order confirmedOrder(long id, List<OrderItem> items, long totalAmount) {
        return Order.restore(id, 1L, OrderStatus.CONFIRMED, items, totalAmount, Instant.now());
    }

    private Product product(long id, int stock) {
        return Product.restore(id, 1L, "상품", null, 1_000L, stock, false, Instant.now());
    }
}
