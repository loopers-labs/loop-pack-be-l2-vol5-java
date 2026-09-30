package com.loopers.domain.user;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class Point {

    @Column(name = "point_balance", nullable = false)
    private long balance;

    protected Point() {}

    public void charge(long amount) {
        if (amount <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "충전 금액은 양수여야 합니다.");
        }
        this.balance += amount;
    }

    /**
     * 차감 후 잔액이 0 이상인지는 현재 잔액에 의존하는 검증이라, charge()와 달리 원자적 UPDATE로 못 옮기고
     * 여기(도메인 객체)에서 캡슐화한다 — 호출자는 비관적 락으로 조회한 매니지드 엔티티에 대고 호출해야 한다
     * (docs/week2/design.md 5번 섹션 "차감 → 비관적 락" 참고).
     */
    public void pay(long amount) {
        if (amount <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "결제 금액은 양수여야 합니다.");
        }
        if (balance - amount < 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "포인트 잔액이 부족합니다.");
        }
        this.balance -= amount;
    }

    public long getBalance() {
        return balance;
    }
}
