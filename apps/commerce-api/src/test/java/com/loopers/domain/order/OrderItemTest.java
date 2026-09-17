package com.loopers.domain.order;

import com.loopers.domain.error.DomainErrorType;
import com.loopers.domain.error.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderItemTest {

    @DisplayName("주문 품목을 생성할 때, ")
    @Nested
    class Create {

        @DisplayName("상품 ID·양수 수량·0 이상 단가면, 금액은 단가×수량이다.")
        @Test
        void calculatesAmount_whenValuesAreValid() {
            // act
            OrderItem item = new OrderItem(1L, 2L, 3_000L);

            // assert
            assertAll(
                () -> assertThat(item.getProductId()).isEqualTo(1L),
                () -> assertThat(item.getQuantity()).isEqualTo(2L),
                () -> assertThat(item.getUnitPrice()).isEqualTo(3_000L),
                () -> assertThat(item.getAmount()).isEqualTo(6_000L)
            );
        }

        @DisplayName("수량이 0 이하면, INVALID_VALUE 예외가 발생한다. (ORD-002)")
        @ParameterizedTest
        @ValueSource(longs = {0L, -1L})
        void throwsInvalidValue_whenQuantityIsNotPositive(long quantity) {
            // act
            DomainException result = assertThrows(DomainException.class, () -> new OrderItem(1L, quantity, 3_000L));

            // assert
            assertThat(result.getType()).isEqualTo(DomainErrorType.INVALID_VALUE);
        }

        @DisplayName("상품 ID가 없으면, INVALID_VALUE 예외가 발생한다.")
        @Test
        void throwsInvalidValue_whenProductIdIsNull() {
            // act
            DomainException result = assertThrows(DomainException.class, () -> new OrderItem(null, 1L, 3_000L));

            // assert
            assertThat(result.getType()).isEqualTo(DomainErrorType.INVALID_VALUE);
        }

        @DisplayName("단가가 음수면, INVALID_VALUE 예외가 발생한다.")
        @Test
        void throwsInvalidValue_whenUnitPriceIsNegative() {
            // act
            DomainException result = assertThrows(DomainException.class, () -> new OrderItem(1L, 1L, -1L));

            // assert
            assertThat(result.getType()).isEqualTo(DomainErrorType.INVALID_VALUE);
        }
    }
}
