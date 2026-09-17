package com.loopers.domain.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @DisplayName("금액을 만들 때, ")
    @Nested
    class Create {

        @DisplayName("M-1 · PNT-01 0원은 유효한 금액이다.")
        @Test
        void createsZero() {
            // act
            Money money = Money.of(0);

            // assert
            assertThat(money.amount()).isEqualTo(0L);
        }

        @DisplayName("M-2 · PNT-01 음수 금액은 만들 수 없다.")
        @Test
        void rejectsNegative() {
            // act & assert
            assertThatThrownBy(() -> Money.of(-1))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @DisplayName("금액을 더할 때, ")
    @Nested
    class Plus {

        @DisplayName("M-3 · PNT-03 3,000원에 7,000원을 더하면 10,000원이다.")
        @Test
        void addsAmounts() {
            // act
            Money result = Money.of(3_000).plus(Money.of(7_000));

            // assert
            assertThat(result).isEqualTo(Money.of(10_000));
        }

        @DisplayName("M-4 · PNT-03 합계가 long 표현 범위를 넘으면 거절한다.")
        @Test
        void rejectsOverflow() {
            // arrange
            Money almostMax = Money.of(Long.MAX_VALUE - 1);

            // act & assert
            assertThatThrownBy(() -> almostMax.plus(Money.of(2)))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @DisplayName("금액을 뺄 때, ")
    @Nested
    class Minus {

        @DisplayName("M-5 · PNT-04 10,000원에서 7,000원을 빼면 3,000원이다.")
        @Test
        void subtractsAmounts() {
            // act
            Money result = Money.of(10_000).minus(Money.of(7_000));

            // assert
            assertThat(result).isEqualTo(Money.of(3_000));
        }

        @DisplayName("M-5 · PNT-04 7,000원에서 7,000원을 빼면 0원이다.")
        @Test
        void subtractsToZero() {
            // act
            Money result = Money.of(7_000).minus(Money.of(7_000));

            // assert
            assertThat(result).isEqualTo(Money.of(0));
        }

        @DisplayName("M-6 · PNT-04 원금보다 큰 금액을 빼면 거절한다.")
        @Test
        void rejectsSubtractingMoreThanAmount() {
            // act & assert
            assertThatThrownBy(() -> Money.of(3_000).minus(Money.of(7_000)))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
