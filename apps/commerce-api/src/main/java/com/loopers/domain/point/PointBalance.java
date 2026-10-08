package com.loopers.domain.point;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import lombok.Getter;

@Getter
public class PointBalance {
    private final Long id;
    private final long userId;
    private long balance;

    private PointBalance(Long id, long userId, long balance) {
        if (userId <= 0 || balance < 0) {
            throw new CoreException(ErrorType.INVALID_REQUEST);
        }
        this.id = id;
        this.userId = userId;
        this.balance = balance;
    }

    public static PointBalance empty(long userId) {
        return new PointBalance(null, userId, 0);
    }

    public static PointBalance restore(long id, long userId, long balance) {
        return new PointBalance(id, userId, balance);
    }

    public void charge(long amount) {
        validateAmount(amount);
        try {
            balance = Math.addExact(balance, amount);
        } catch (ArithmeticException exception) {
            throw new CoreException(ErrorType.INVALID_REQUEST);
        }
    }

    public void deduct(long amount) {
        validateAmount(amount);
        if (amount > balance) {
            throw new CoreException(ErrorType.INSUFFICIENT_POINTS);
        }
        balance -= amount;
    }

    public static void validateAmount(long amount) {
        if (amount <= 0) {
            throw new CoreException(ErrorType.INVALID_REQUEST);
        }
    }
}
