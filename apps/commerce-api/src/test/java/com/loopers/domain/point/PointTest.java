package com.loopers.domain.point;

import com.loopers.domain.common.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PointTest {
    @Test
    @DisplayName("0원에서 10000원 충전 후 7000원 결제하면 3000원이 남는다")
    void chargesAndPays() {
        Point point = new Point(1, new Money(0));
        point.charge(10000);
        point.pay(new Money(7000));
        assertThat(point.getBalance()).isEqualTo(new Money(3000));
    }
    @Test
    @DisplayName("0원 충전과 잔액 부족 결제와 합산 범위 초과는 기존 잔액을 유지한다")
    void rejectsAndPreservesBalance() {
        Point point = new Point(1, new Money(100));
        assertThatThrownBy(() -> point.charge(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> point.pay(new Money(101))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> point.charge(Long.MAX_VALUE)).isInstanceOf(ArithmeticException.class);
        assertThat(point.getBalance()).isEqualTo(new Money(100));
    }
}
