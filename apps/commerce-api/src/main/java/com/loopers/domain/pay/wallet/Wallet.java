package com.loopers.domain.pay.wallet;

import com.loopers.domain.shared.Money;
import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;

// 사용자 포인트 잔액을 보관하는 지갑 도메인 모델
public final class Wallet {
    private final long userId;
    private Money balance;

    private Wallet(long userId, Money balance) {
        if (userId <= 0) {
            throw new IllegalArgumentException("사용자 ID는 양수여야 합니다.");
        }
        this.userId = userId;
        this.balance = balance;
    }

    public static Wallet zero(long userId) {
        return new Wallet(userId, Money.zero());
    }

    public static Wallet restore(long userId, long balance) {
        return new Wallet(userId, Money.of(balance));
    }

    // 잔액을 충전하고 충전 기록을 반환
    public PointBill charge(Money amount) {
        balance = balance.add(amount);
        return PointBill.charge(userId, amount.getValue());
    }

    // 차감 가능 여부만 검증하고 잔액은 바꾸지 않음
    public void ensureSufficientBalance(Money amount) {
        if (amount.getValue() > balance.getValue()) {
            throw new DomainException(DomainErrorCode.INSUFFICIENT_POINT);
        }
    }

    // 잔액을 차감하고 사용 기록을 반환
    public PointBill use(Money amount, long orderId) {
        ensureSufficientBalance(amount);
        balance = Money.of(balance.getValue() - amount.getValue());
        return PointBill.use(userId, orderId, amount.getValue());
    }

    public long getUserId() {
        return userId;
    }

    public long getBalance() {
        return balance.getValue();
    }
}
