package com.loopers.domain.order;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderItemTest {
    @DisplayName("주문 품목을 생성할 때, ")
    @Nested
    class Create {
        @DisplayName("상품·수량·단가가 주어지면, 주문 시점의 수량과 단가가 기록된다.")
        @Test
        void recordsQuantityAndUnitPrice_whenProductAndQuantityAndUnitPriceAreProvided() {
            // act
            OrderItem item = new OrderItem(1L, 2, 1000L);

            // assert
            assertThat(item.getProductId()).isEqualTo(1L);
            assertThat(item.getQuantity()).isEqualTo(2);
            assertThat(item.getUnitPrice()).isEqualTo(1000L);
        }

        @DisplayName("수량이 0 이하이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenQuantityIsNotPositive() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                new OrderItem(1L, 0, 1000L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("단가가 음수이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenUnitPriceIsNegative() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                new OrderItem(1L, 2, -1L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }

    @DisplayName("주문 품목 금액을 계산할 때, ")
    @Nested
    class Amount {
        @DisplayName("금액은 단가 × 수량과 같다.")
        @Test
        void calculatesAmountAsUnitPriceTimesQuantity() {
            // act
            OrderItem item = new OrderItem(1L, 3, 1000L);

            // assert
            assertThat(item.getAmount()).isEqualTo(3000L);
        }

        @DisplayName("이후 상품 가격이 바뀌어도, 주문 시점 단가로 계산된다.")
        @Test
        void usesRecordedUnitPrice_whenProductPriceChangesLater() {
            // arrange - 주문 시점 단가를 1000원으로 기록한다
            OrderItem item = new OrderItem(1L, 2, 1000L);

            // act - 이후 상품 가격이 바뀌어도 품목은 영향받지 않는다
            long amount = item.getAmount();

            // assert
            assertThat(amount).isEqualTo(2000L);
            assertThat(item.getUnitPrice()).isEqualTo(1000L);
        }
    }
}
