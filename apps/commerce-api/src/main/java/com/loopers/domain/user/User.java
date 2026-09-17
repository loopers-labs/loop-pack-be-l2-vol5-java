package com.loopers.domain.user;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.error.DomainErrorType;
import com.loopers.domain.error.DomainException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User extends BaseEntity {

    private String name;

    // 1포인트 = 1원. 사용자와 함께 0원으로 생긴다(P-5)
    @Column(nullable = false)
    private Long balance = 0L;

    protected User() {}

    public User(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public Long getBalance() {
        return balance;
    }

    public void charge(Long amount) {
        if (amount == null || amount <= 0) {
            throw new DomainException(DomainErrorType.INVALID_VALUE, "충전 금액은 1 이상이어야 합니다.");
        }
        try {
            this.balance = Math.addExact(balance, amount);
        } catch (ArithmeticException e) {
            throw new DomainException(DomainErrorType.INVALID_VALUE, "충전 후 잔액이 허용 범위를 넘습니다.");
        }
    }

    public void use(long amount) {
        if (amount < 0) {
            throw new DomainException(DomainErrorType.INVALID_VALUE, "사용 금액은 0 이상이어야 합니다.");
        }
        if (amount > balance) {
            throw new DomainException(DomainErrorType.CONFLICT, "포인트 잔액이 부족합니다.");
        }
        this.balance -= amount;
    }
}
