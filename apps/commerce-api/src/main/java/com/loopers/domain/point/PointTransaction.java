package com.loopers.domain.point;

import com.loopers.domain.common.Money;

import java.time.Instant;

public record PointTransaction(
    TransactionType type,
    Money amount,
    Money balanceAfter,
    Instant occurredAt
) {
    static PointTransaction charge(ChargeAmount amount, Money balanceAfter, Instant occurredAt) {
        return new PointTransaction(TransactionType.CHARGE, amount.toMoney(), balanceAfter, occurredAt);
    }

    static PointTransaction use(Money amount, Money balanceAfter, Instant occurredAt) {
        return new PointTransaction(TransactionType.USE, amount, balanceAfter, occurredAt);
    }
}
