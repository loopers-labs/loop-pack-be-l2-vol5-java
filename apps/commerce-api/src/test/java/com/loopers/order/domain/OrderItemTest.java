package com.loopers.order.domain;

import com.loopers.product.domain.Product;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderItemTest {

    @DisplayName("[R-ORDER-02] 주문 품목에는 상품·수량·단가가 포함된다.")
    @Nested
    class SnapshotProduct {

        @DisplayName("[동등 클래스 분할] 상품의 식별자·이름·단가와 주문 수량을 기록한다.")
        @Test
        void snapshotsProductAndQuantity() {
            Product product = new Product(1L, "상품", 2_000L);

            OrderItem item = OrderItem.of(product, 3);

            assertAll(
                () -> assertThat(item.productId()).isEqualTo(product.getId()),
                () -> assertThat(item.productName()).isEqualTo("상품"),
                () -> assertThat(item.quantity()).isEqualTo(3),
                () -> assertThat(item.unitPrice()).isEqualTo(2_000L),
                () -> assertThat(item.amount()).isEqualTo(6_000L)
            );
        }
    }

    @DisplayName("[R-ORDER-06] 주문할 상품의 수량은 양수여야 한다.")
    @Nested
    class PositiveQuantity {

        @DisplayName("[경계값 분석] 수량 0과 -1은 주문 수량 오류로 거절한다.")
        @ParameterizedTest
        @ValueSource(ints = {0, -1})
        void throwsInvalidOrderQuantity_whenQuantityIsNotPositive(int quantity) {
            CoreException result = assertThrows(
                CoreException.class,
                () -> new OrderItem(1L, "상품", quantity, 2_000L)
            );

            assertThat(result.getErrorCode()).isEqualTo(ErrorCode.INVALID_ORDER_QUANTITY);
        }
    }

    @DisplayName("[P-ORDER-03] 주문 품목은 주문을 생성할 때 기록한 단가를 유지한다.")
    @Nested
    class KeepUnitPrice {

        @DisplayName("[상태 전이] 수량을 바꿔도 단가와 상품 이름은 유지한다.")
        @Test
        void keepsSnapshot_whenQuantityChanges() {
            OrderItem item = new OrderItem(1L, "상품", 2, 2_000L);

            OrderItem result = item.withQuantity(3);

            assertAll(
                () -> assertThat(result.productId()).isEqualTo(1L),
                () -> assertThat(result.productName()).isEqualTo("상품"),
                () -> assertThat(result.quantity()).isEqualTo(3),
                () -> assertThat(result.unitPrice()).isEqualTo(2_000L)
            );
        }
    }

    @DisplayName("[P-ORDER-07] 주문 품목은 주문 당시의 상품 정보를 보여 준다.")
    @Nested
    class KeepProductSnapshot {

        @DisplayName("[상태 전이] 원본 상품을 수정하거나 삭제해도 품목 정보는 그대로다.")
        @Test
        void keepsSnapshot_whenProductChanges() {
            Product product = new Product(1L, "주문 당시 상품", 2_000L);
            OrderItem item = OrderItem.of(product, 2);

            product.update("수정 상품", 3_000L, 1L);
            product.delete();

            assertAll(
                () -> assertThat(item.productName()).isEqualTo("주문 당시 상품"),
                () -> assertThat(item.unitPrice()).isEqualTo(2_000L),
                () -> assertThat(item.amount()).isEqualTo(4_000L)
            );
        }
    }
}
