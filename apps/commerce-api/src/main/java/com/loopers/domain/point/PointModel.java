package com.loopers.domain.point;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * AG-05 포인트 (TB-05 point). 사용자마다 계정 하나 (DR-12, IX-05 고유), 잔액은 단일 저장값 (DR-07).
 * 충전·환불·운영자 충전/차감은 이 루트의 행위다. INV-01 잔액 ≥ 0, INV-02 표현 범위(64비트) 안.
 */
@Entity
@Table(name = "point", uniqueConstraints = @UniqueConstraint(name = "ux_point_user", columnNames = "user_id"))
public class PointModel extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "balance", nullable = false)
    private Long balance;

    protected PointModel() {}

    public PointModel(Long userId, Long balance) {
        if (userId == null) {
            throw new CoreException(ErrorType.USER_NOT_FOUND, "포인트 계정의 사용자가 지정되지 않았습니다.");
        }
        if (balance == null || balance < 0) {
            throw new CoreException(ErrorType.INVALID_AMOUNT, "초기 잔액은 0 이상이어야 합니다. [balance = " + balance + "]");
        }
        this.userId = userId;
        this.balance = balance;
    }

    /** FR-POINT-01·FR-ADMIN-POINT-01 사전 조건: amount 존재, 양수 (표현 범위는 타입 경계, DR-11). ER-09 INVALID_AMOUNT. */
    private static long validateAmount(Long amount) {
        if (amount == null || amount <= 0) {
            throw new CoreException(ErrorType.INVALID_AMOUNT, "금액은 양수여야 합니다. [amount = " + amount + "]");
        }
        return amount;
    }

    /** 잔액 += amount. 합산이 표현 범위를 넘으면 INV-02 위반, ER-10 BALANCE_LIMIT_EXCEEDED (잔액 유지). */
    public void charge(Long amount) {
        long valid = validateAmount(amount);
        try {
            this.balance = Math.addExact(this.balance, valid);
        } catch (ArithmeticException e) {
            throw new CoreException(ErrorType.BALANCE_LIMIT_EXCEEDED,
                "잔액 한도를 초과합니다. [balance = " + balance + ", amount = " + valid + "]");
        }
    }

    /** 잔액 −= amount. 잔액 < amount 면 INV-01 위반, ER-11 INSUFFICIENT_POINT (잔액 유지). 환불·운영자 차감·주문 확정 공통. */
    public void deduct(Long amount) {
        long valid = validateAmount(amount);
        if (this.balance < valid) {
            throw new CoreException(ErrorType.INSUFFICIENT_POINT,
                "포인트가 부족합니다. [balance = " + balance + ", amount = " + valid + "]");
        }
        this.balance -= valid;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getBalance() {
        return balance;
    }
}
