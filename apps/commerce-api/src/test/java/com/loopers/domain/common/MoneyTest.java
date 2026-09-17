package com.loopers.domain.common;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Money 는 원 단위 금액의 계산과 표현 범위를 책임진다.")
class MoneyTest {

    @DisplayName("생성")
    @Nested
    class Create {
        @DisplayName("0원은 유효한 금액이다.")
        @Test
        void allowsZero() {
            Money money = Money.of(0L);

            assertThat(money.toWon()).isZero();
        }

        @DisplayName("음수 금액은 생성할 수 없다.")
        @Test
        void rejectsNegativeAmount() {
            assertThatThrownBy(() -> Money.of(-1L))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.BAD_REQUEST);
        }
    }

    @DisplayName("덧셈")
    @Nested
    class Add {
        @DisplayName("두 금액을 더한 새 Money 를 반환한다.")
        @Test
        void returnsSum() {
            Money sum = Money.of(1_000L).add(Money.of(2_000L));

            assertThat(sum.toWon()).isEqualTo(3_000L);
        }

        @DisplayName("더한 결과가 표현 범위를 넘으면 NUMERIC_OVERFLOW 로 거절한다.")
        @Test
        void rejectsOverflow() {
            Money max = Money.of(Long.MAX_VALUE);

            assertThatThrownBy(() -> max.add(Money.of(1L)))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.NUMERIC_OVERFLOW);
        }

        @DisplayName("원본 Money 의 값은 변하지 않는다.")
        @Test
        void keepsOperandsUnchanged() {
            Money original = Money.of(1_000L);

            original.add(Money.of(2_000L));

            assertThat(original.toWon()).isEqualTo(1_000L);
        }
    }

    @DisplayName("수량 곱셈")
    @Nested
    class Multiply {
        @DisplayName("수량만큼 곱한 새 Money 를 반환한다.")
        @Test
        void returnsProduct() {
            Money amount = Money.of(1_500L).multiply(3L);

            assertThat(amount.toWon()).isEqualTo(4_500L);
        }

        @DisplayName("수량 0 을 곱하면 0원이다.")
        @Test
        void returnsZero_whenQuantityIsZero() {
            Money amount = Money.of(1_500L).multiply(0L);

            assertThat(amount.toWon()).isZero();
        }

        @DisplayName("음수 수량은 곱할 수 없다.")
        @Test
        void rejectsNegativeQuantity() {
            Money price = Money.of(1_500L);

            assertThatThrownBy(() -> price.multiply(-1L))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("곱한 결과가 표현 범위를 넘으면 NUMERIC_OVERFLOW 로 거절한다.")
        @Test
        void rejectsOverflow() {
            Money price = Money.of(Long.MAX_VALUE / 2 + 1L);

            assertThatThrownBy(() -> price.multiply(2L))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.NUMERIC_OVERFLOW);
        }
    }

    @DisplayName("동등성")
    @Nested
    class Equality {
        @DisplayName("같은 금액의 Money 는 서로 같다.")
        @Test
        void equalsByValue() {
            assertThat(Money.of(1_000L)).isEqualTo(Money.of(1_000L));
            assertThat(Money.of(1_000L)).isNotEqualTo(Money.of(2_000L));
        }
    }

    @DisplayName("비교")
    @Nested
    class Compare {
        @DisplayName("금액의 크기를 비교한다.")
        @Test
        void comparesByWon() {
            assertThat(Money.of(2_000L)).isGreaterThan(Money.of(1_000L));
            assertThat(Money.of(1_000L)).isEqualByComparingTo(Money.of(1_000L));
        }
    }
}
