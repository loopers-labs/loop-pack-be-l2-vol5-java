package com.loopers.domain.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {
    @Test
    @DisplayName("금액 덧셈과 곱셈과 차감은 새 금액을 반환하고 원본을 유지한다")
    void calculatesWithoutMutation() {
        Money money = new Money(100);
        assertThat(money.add(new Money(20))).isEqualTo(new Money(120));
        assertThat(money.multiply(3)).isEqualTo(new Money(300));
        assertThat(money.subtract(new Money(100))).isEqualTo(new Money(0));
        assertThat(money.value()).isEqualTo(100);
    }

    @Test
    @DisplayName("음수 금액과 음수 배수와 보유액 초과 차감은 거절한다")
    void rejectsInvalidAmounts() {
        assertThatThrownBy(() -> new Money(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Money(1).multiply(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Money(1).subtract(new Money(2))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("long 범위를 넘는 덧셈과 곱셈은 거절한다")
    void rejectsOverflow() {
        Money maximum = new Money(Long.MAX_VALUE);
        assertThatThrownBy(() -> maximum.add(new Money(1))).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> maximum.multiply(2)).isInstanceOf(ArithmeticException.class);
    }
}
