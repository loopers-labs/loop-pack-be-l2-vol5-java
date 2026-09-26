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

    @DisplayName("[INV-39] 품목 수량은 양수다.")
    @Nested
    class PositiveQuantity {

        @DisplayName("[경계값 분석] 수량 1로 품목을 만들 수 있다.")
        @Test
        void createsItem_whenQuantityIsOne() {
            // act
            OrderItem result = new OrderItem(1L, "상품", 1, 2_000L);

            // assert
            assertThat(result.quantity()).isEqualTo(1);
        }

        @DisplayName("[경계값 분석] 수량 0과 -1은 주문 수량 오류로 거절한다.")
        @ParameterizedTest
        @ValueSource(ints = {0, -1})
        void throwsInvalidOrderQuantity_whenQuantityIsNotPositive(int quantity) {
            // act
            CoreException result = assertThrows(
                CoreException.class,
                () -> new OrderItem(1L, "상품", quantity, 2_000L)
            );

            // assert
            assertThat(result.getErrorCode()).isEqualTo(ErrorCode.INVALID_ORDER_QUANTITY);
        }

        @DisplayName("[경계값 분석] 수량을 0으로 바꾸면 주문 수량 오류로 거절하고, 품목은 그대로다.")
        @Test
        void throwsInvalidOrderQuantity_whenChangedQuantityIsNotPositive() {
            // arrange
            OrderItem item = new OrderItem(1L, "상품", 2, 2_000L);

            // act
            CoreException result = assertThrows(
                CoreException.class, () -> item.withQuantity(0));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ErrorCode.INVALID_ORDER_QUANTITY),
                () -> assertThat(item.quantity()).isEqualTo(2)
            );
        }
    }

    @DisplayName("[INV-40] 품목은 주문을 생성한 시점의 상품 이름과 단가를 갖는다.")
    @Nested
    class SnapshotAtCreation {

        @DisplayName("[동등 클래스 분할] 상품의 식별자·이름·단가와 주문 수량을 기록한다.")
        @Test
        void snapshotsProductAndQuantity() {
            // arrange
            Product product = new Product(1L, "상품", 2_000L);

            // act
            OrderItem item = OrderItem.of(product, 3);

            // assert
            assertAll(
                () -> assertThat(item.productId()).isEqualTo(product.getId()),
                () -> assertThat(item.productName()).isEqualTo("상품"),
                () -> assertThat(item.quantity()).isEqualTo(3),
                () -> assertThat(item.unitPrice()).isEqualTo(2_000L),
                () -> assertThat(item.amount()).isEqualTo(6_000L)
            );
        }

        @DisplayName("[상태 전이] 수량을 바꿔도 상품 이름과 단가는 그대로다.")
        @Test
        void keepsSnapshot_whenQuantityChanges() {
            // arrange
            OrderItem item = new OrderItem(1L, "상품", 2, 2_000L);

            // act
            OrderItem result = item.withQuantity(3);

            // assert
            assertAll(
                () -> assertThat(result.productId()).isEqualTo(1L),
                () -> assertThat(result.productName()).isEqualTo("상품"),
                () -> assertThat(result.quantity()).isEqualTo(3),
                () -> assertThat(result.unitPrice()).isEqualTo(2_000L)
            );
        }

        @DisplayName("[상태 전이] 원본 상품의 이름과 가격을 수정해도 품목의 값은 그대로다.")
        @Test
        void keepsSnapshot_whenProductChanges() {
            // arrange
            Product product = new Product(1L, "주문 당시 상품", 2_000L);
            OrderItem item = OrderItem.of(product, 2);

            // act
            product.update("수정 상품", 3_000L, 1L);

            // assert
            assertAll(
                () -> assertThat(item.productName()).isEqualTo("주문 당시 상품"),
                () -> assertThat(item.unitPrice()).isEqualTo(2_000L),
                () -> assertThat(item.amount()).isEqualTo(4_000L)
            );
        }
    }

    @DisplayName("[INV-41] 상품이 삭제되어도 그 상품의 주문 품목은 남는다.")
    @Nested
    class SurvivesProductDeletion {

        @DisplayName("[상태 전이] 원본 상품을 삭제해도 품목은 상품 참조와 값을 그대로 갖는다.")
        @Test
        void keepsItem_whenProductIsDeleted() {
            // arrange
            Product product = new Product(1L, "주문 당시 상품", 2_000L);
            OrderItem item = OrderItem.of(product, 2);

            // act
            product.delete();

            // assert
            assertAll(
                () -> assertThat(item.productId()).isEqualTo(product.getId()),
                () -> assertThat(item.productName()).isEqualTo("주문 당시 상품"),
                () -> assertThat(item.unitPrice()).isEqualTo(2_000L),
                () -> assertThat(item.amount()).isEqualTo(4_000L)
            );
        }
    }
}
