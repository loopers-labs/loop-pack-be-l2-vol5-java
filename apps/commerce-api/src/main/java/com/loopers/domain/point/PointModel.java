package com.loopers.domain.point;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * 사용자별 포인트 잔액의 기준. 1포인트는 1원이며 잔액은 0을 허용하지만 음수가 될 수 없다.
 */
@Entity
@Table(name = "point")
@Getter
public class PointModel extends BaseEntity {

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(nullable = false)
    private long balance;

    protected PointModel() {}

    private PointModel(Long userId) {
        this.userId = userId;
        this.balance = 0L;
    }

    public static PointModel of(Long userId) {
        return new PointModel(userId);
    }

    public PointChange charge(long amount) {
        requirePositiveAmount(amount);

        long before = this.balance;
        long after;
        try {
            after = Math.addExact(before, amount);
        } catch (ArithmeticException e) {
            throw new CoreException(ErrorType.NUMERIC_OVERFLOW);
        }

        this.balance = after;
        return new PointChange(before, after, amount);
    }

    public PointChange use(long amount) {
        requirePositiveAmount(amount);

        long before = this.balance;
        if (before < amount) {
            throw new CoreException(ErrorType.INSUFFICIENT_POINT);
        }

        long after = before - amount;
        this.balance = after;
        return new PointChange(before, after, amount);
    }

    private void requirePositiveAmount(long amount) {
        if (amount < 1L) {
            throw new CoreException(ErrorType.INVALID_POINT_AMOUNT);
        }
    }
}
