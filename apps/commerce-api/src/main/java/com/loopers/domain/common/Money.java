package com.loopers.domain.common;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.util.Objects;

/**
 * 원 단위 금액을 표현하는 Value Object.
 * 0원은 허용하고 음수 금액은 허용하지 않으며, 계산 결과가 long 범위를 넘으면 거절한다.
 */
@Embeddable
public class Money implements Comparable<Money> {

    @Column(nullable = false)
    private long won;

    protected Money() {}

    private Money(long won) {
        this.won = won;
    }

    public static Money of(long won) {
        if (won < 0L) {
            throw new CoreException(ErrorType.BAD_REQUEST, "금액은 0원 이상이어야 합니다. [won = " + won + "]");
        }
        return new Money(won);
    }

    public Money add(Money other) {
        try {
            return Money.of(Math.addExact(this.won, other.won));
        } catch (ArithmeticException e) {
            throw new CoreException(ErrorType.NUMERIC_OVERFLOW);
        }
    }

    public Money multiply(long quantity) {
        if (quantity < 0L) {
            throw new CoreException(ErrorType.BAD_REQUEST, "수량은 0 이상이어야 합니다. [quantity = " + quantity + "]");
        }
        try {
            return Money.of(Math.multiplyExact(this.won, quantity));
        } catch (ArithmeticException e) {
            throw new CoreException(ErrorType.NUMERIC_OVERFLOW);
        }
    }

    public long toWon() {
        return won;
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(this.won, other.won);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Money money)) {
            return false;
        }
        return this.won == money.won;
    }

    @Override
    public int hashCode() {
        return Objects.hash(won);
    }

    @Override
    public String toString() {
        return won + "원";
    }
}
