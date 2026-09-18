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
        @DisplayName("유효한 값이 주어지면, 소계가 단가 곱하기 수량으로 계산된다.")
        @Test
        void calculatesSubtotal_whenValuesAreValid() {
            // act
            OrderItem item = new OrderItem(1L, 3, 10_000L);

            // assert
            assertThat(item.getSubtotal()).isEqualTo(30_000L);
        }

        @DisplayName("수량이 0 이하이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenQuantityIsNotPositive() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> new OrderItem(1L, 0, 10_000L));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }
}
