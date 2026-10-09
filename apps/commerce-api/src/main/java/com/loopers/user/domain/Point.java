package com.loopers.user.domain;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import com.loopers.domain.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "point")
public class Point extends BaseEntity {

    @Column(nullable = false, unique = true)
    private Long userId;

    private long balance;
    @Version
    private Long version;

    protected Point() {
    }

    public Point(long balance) {
        this(null, balance);
    }

    public Point(Long userId, long balance) {
        if (balance < 0) {
            throw new CoreException(ErrorCode.INVALID_POINT_BALANCE);
        }
        this.userId = userId;
        this.balance = balance;
    }

    public Long getUserId() { return userId; }

    public long balance() {
        return balance;
    }

    public Point charge(long amount) {
        if (amount <= 0) {
            throw new CoreException(ErrorCode.INVALID_CHARGE_AMOUNT);
        }
        try {
            return new Point(userId, Math.addExact(balance, amount));
        } catch (ArithmeticException exception) {
            throw new CoreException(ErrorCode.POINT_BALANCE_LIMIT_EXCEEDED);
        }
    }

    public Point pay(long amount) {
        if (amount > balance) {
            throw new CoreException(ErrorCode.INSUFFICIENT_POINT);
        }
        return new Point(userId, balance - amount);
    }

    public void changeBalance(long balance) {
        if (balance < 0) {
            throw new CoreException(ErrorCode.INVALID_POINT_BALANCE);
        }
        this.balance = balance;
    }
}
