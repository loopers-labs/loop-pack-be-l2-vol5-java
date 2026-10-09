package com.loopers.domain.point;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

/**
 * 사용자의 포인트 잔액. 1포인트는 1원이다. 사용자 한 명에 하나이며 사용자는 식별자로만 보관한다 (설계 2.3).
 * 잔액 0원은 유효하지만 충전 0원은 충전이 아니다.
 */
@Entity
@Table(name = "points", uniqueConstraints = @UniqueConstraint(name = "uk_points_user", columnNames = "user_id"))
public class Point extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    private long balance;

    /** 같은 사용자의 충전 · 결제가 겹치면 나중 commit 이 충돌함. 충돌한 유스케이스는 ~Retrier 가 새 트랜잭션으로 다시 실행 (3주차 설계 4.1) */
    @Version
    private Long version;

    protected Point() {}

    /** 충전한 적 없는 사용자의 잔액 0원 Point. */
    public Point(Long userId) {
        this.userId = userId;
        this.balance = 0L;
    }

    public Long getUserId() {
        return userId;
    }

    public long getBalance() {
        return balance;
    }

    /** 충전액은 양수여야 하고(PNT-01), 합계가 저장 타입의 범위를 넘으면 거절한다(PNT-02). */
    public void charge(long amount) {
        if (amount <= 0) {
            throw new CoreException(PointErrorCode.INVALID_CHARGE_AMOUNT);
        }
        try {
            this.balance = Math.addExact(balance, amount);
        } catch (ArithmeticException e) {
            throw new CoreException(PointErrorCode.BALANCE_LIMIT_EXCEEDED);
        }
    }

    /** 0원 결제는 허용함 (설계 D-30). 음수이거나 잔액보다 많으면 거절하므로 잔액은 0 아래로 내려가지 않음 (PNT-03) */
    public void pay(long amount) {
        if (amount < 0 || amount > balance) {
            throw new CoreException(PointErrorCode.INSUFFICIENT_POINT);
        }
        this.balance -= amount;
    }
}
