package com.loopers.domain.common;

/**
 * 원 단위 정수 금액. 음수를 만들 수 없고, 연산은 새 값을 반환한다.
 * 값의 유효성만 책임지므로 오류는 IllegalArgumentException으로 알린다 — 의미 해석은 사용하는 도메인이 한다 (ADR-12).
 */
public record Money(long amount) {

    public static final Money ZERO = new Money(0);

    public Money {
        if (amount < 0) {
            throw new IllegalArgumentException("금액은 음수일 수 없습니다.");
        }
    }

    public static Money of(long amount) {
        return new Money(amount);
    }

    public Money plus(Money other) {
        try {
            return new Money(Math.addExact(amount, other.amount));
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("금액 합계가 표현 범위를 넘습니다.", e);
        }
    }

    public Money minus(Money other) {
        if (other.isGreaterThan(this)) {
            throw new IllegalArgumentException("차감액이 원금을 넘습니다.");
        }
        return new Money(amount - other.amount);
    }

    public boolean isGreaterThan(Money other) {
        return amount > other.amount;
    }

    public boolean isZero() {
        return amount == 0;
    }
}
