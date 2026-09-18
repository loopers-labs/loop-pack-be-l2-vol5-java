package com.loopers.user.domain;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import jakarta.persistence.Embeddable;

@Embeddable
public class Point {

    private long balance;

    protected Point() {
    }

    public Point(long balance) {
        this.balance = balance;
    }

    public long balance() {
        return balance;
    }

    public Point charge(long amount) {
        if (amount <= 0) {
            throw new CoreException(ErrorCode.INVALID_CHARGE_AMOUNT);
        }
        try {
            return new Point(Math.addExact(balance, amount));
        } catch (ArithmeticException exception) {
            throw new CoreException(ErrorCode.POINT_BALANCE_LIMIT_EXCEEDED);
        }
    }

    public Point pay(long amount) {
        if (amount > balance) {
            throw new CoreException(ErrorCode.INSUFFICIENT_POINT);
        }
        return new Point(balance - amount);
    }
}
