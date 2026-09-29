package com.loopers.domain.product;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PriceTest {
    @DisplayName("금액을 생성할 때, ")
    @Nested
    class Create {
        @DisplayName("양수 금액이 주어지면, 해당 금액으로 생성된다.")
        @Test
        void createsPrice_whenAmountIsPositive() {
            // act
            Price price = new Price(1000L);

            // assert
            assertThat(price.getAmount()).isEqualTo(1000L);
        }

        @DisplayName("0원이 주어지면, 무료 상품 금액으로 생성된다.")
        @Test
        void createsPrice_whenAmountIsZero() {
            // act
            Price price = new Price(0L);

            // assert
            assertThat(price.getAmount()).isZero();
        }

        @DisplayName("음수 금액이 주어지면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenAmountIsNegative() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                new Price(-1L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }

    @DisplayName("금액을 비교할 때, ")
    @Nested
    class Equality {
        @DisplayName("금액이 같으면, 같은 값으로 판별된다.")
        @Test
        void identifiesAsSame_whenAmountIsEqual() {
            // assert
            assertThat(new Price(1000L)).isEqualTo(new Price(1000L));
        }

        @DisplayName("금액이 다르면, 다른 값으로 판별된다.")
        @Test
        void identifiesAsDifferent_whenAmountDiffers() {
            // assert
            assertThat(new Price(1000L)).isNotEqualTo(new Price(2000L));
        }
    }
}
