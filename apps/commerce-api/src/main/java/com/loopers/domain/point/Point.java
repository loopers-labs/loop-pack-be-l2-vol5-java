package com.loopers.domain.point;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

/**
 * 사용자당 하나만 존재하는 포인트 잔액.
 */
@Getter
@Entity
@Table(
    name = "points",
    uniqueConstraints = @UniqueConstraint(name = "uk_points_user", columnNames = "user_id")
)
public class Point extends BaseEntity {

    private Long userId;

    private long balance;

    protected Point() {}

    public Point(Long userId) {
        if (userId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "포인트의 사용자 식별자는 필수입니다.");
        }
        this.userId = userId;
        this.balance = 0L;
    }

    /**
     * 충전에 실패하면 기존 잔액을 그대로 둔다.
     */
    public void charge(long amount) {
        if (amount <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "충전 금액은 1원 이상이어야 합니다.");
        }
        if (balance > Long.MAX_VALUE - amount) {
            throw new CoreException(ErrorType.BAD_REQUEST, "충전 결과가 표현 가능한 범위를 넘습니다.");
        }
        this.balance += amount;
    }

    public void deduct(long amount) {
        if (amount <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "차감 금액은 1원 이상이어야 합니다.");
        }
        if (balance < amount) {
            throw new CoreException(ErrorType.CONFLICT, "포인트 잔액이 부족합니다.");
        }
        this.balance -= amount;
    }
}
