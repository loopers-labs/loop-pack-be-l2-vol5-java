package com.loopers.domain.point;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "points")
public class PointModel extends BaseEntity {

    public static final long MAX_CHARGE_AMOUNT = 1_000_000L;
    public static final long MAX_BALANCE = 10_000_000L;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "balance", nullable = false)
    private long balance;

    protected PointModel() {
    }

    public PointModel(Long userId) {
        if (userId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "사용자 ID는 비어있을 수 없습니다.");
        }
        this.userId = userId;
        this.balance = 0L;
    }

    public Long getUserId() {
        return userId;
    }

    public long getBalance() {
        return balance;
    }

    public void charge(Long amount) {
        if (amount == null || amount <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "충전액은 양의 정수여야 합니다.");
        }
        if (amount > MAX_CHARGE_AMOUNT) {
            throw new CoreException(ErrorType.BAD_REQUEST, "1회 충전 한도를 초과했습니다. (한도: " + MAX_CHARGE_AMOUNT + ")");
        }
        if (balance + amount > MAX_BALANCE) {
            throw new CoreException(ErrorType.BAD_REQUEST, "잔액 한도를 초과했습니다. (한도: " + MAX_BALANCE + ")");
        }
        this.balance += amount;
    }

    public void use(Long amount) {
        if (amount == null || amount <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "사용액은 양의 정수여야 합니다.");
        }
        if (amount > balance) {
            throw new CoreException(ErrorType.BAD_REQUEST, "잔액이 부족합니다. (잔액: " + balance + ", 요청: " + amount + ")");
        }
        this.balance -= amount;
    }
}
