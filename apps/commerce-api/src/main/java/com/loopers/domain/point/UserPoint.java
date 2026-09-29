package com.loopers.domain.point;

import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
import com.loopers.domain.common.Money;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class UserPoint {

    private final Long userId;
    private Money balance;

    private final List<PointTransaction> newTransactions = new ArrayList<>();

    private UserPoint(Long userId, Money balance) {
        this.userId = userId;
        this.balance = balance;
    }

    public static UserPoint open(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 는 필수입니다.");
        }
        return new UserPoint(userId, Money.ZERO);
    }

    public static UserPoint restore(Long userId, Money balance) {
        return new UserPoint(userId, balance);
    }

    public void charge(ChargeAmount amount, Instant occurredAt) {
        Money next;
        try {
            next = balance.plus(amount.toMoney());
        } catch (ArithmeticException e) {
            throw new DomainException(DomainError.POINT_BALANCE_EXCEEDED,
                "잔액 " + balance.amount() + " 에 " + amount.value() + " 을 더하면 표현 범위를 넘습니다.");
        }
        this.balance = next;
        this.newTransactions.add(PointTransaction.charge(amount, next, occurredAt));
    }

    public void use(Money amount, Instant now) {
        if (amount == null || !amount.isPositive()) {
            throw new IllegalArgumentException("사용액은 양수여야 합니다");
        }
        if (balance.isLessThan(amount)) {
            throw new DomainException(DomainError.INSUFFICIENT_POINT);
        }
        this.balance = balance.minus(amount);
        newTransactions.add(PointTransaction.use(amount, balance, now));
    }

    public List<PointTransaction> pullNewTransactions() {
        List<PointTransaction> pulled = List.copyOf(newTransactions);
        newTransactions.clear();
        return pulled;
    }

    public Long getUserId() {
        return userId;
    }

    public Money getBalance() {
        return balance;
    }
}
