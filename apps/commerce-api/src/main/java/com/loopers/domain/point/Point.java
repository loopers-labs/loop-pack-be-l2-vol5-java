package com.loopers.domain.point;

import com.loopers.domain.common.Money;

public final class Point {
    private final long userId;
    private Money balance;
    public Point(long userId, Money balance) {
        this.userId = userId;
        this.balance = java.util.Objects.requireNonNull(balance);
    }
    public void charge(long amount) {
        if (amount <= 0) { throw new com.loopers.domain.common.InvalidValueException("충전액은 양수여야 합니다."); }
        balance = balance.add(new Money(amount));
    }
    public void pay(Money amount) { balance = balance.subtract(amount); }
    public long getUserId() { return userId; }
    public Money getBalance() { return balance; }
}
