package com.loopers.domain.point;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

public record PointBalance(long amount) {
    public PointBalance {
        if (amount < 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "포인트 잔액은 0 이상이어야 합니다.");
        }
    }

    public PointBalance add(long chargeAmount) {
        if (chargeAmount <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "충전액은 양수여야 합니다.");
        }

        if (exceedsMaximumBalance(chargeAmount)) {
            throw new CoreException(ErrorType.BAD_REQUEST, "충전 후 잔액이 저장 가능한 범위를 초과했습니다.");
        }

        return new PointBalance(amount + chargeAmount);
    }

    private boolean exceedsMaximumBalance(long chargeAmount) {
        return amount > Long.MAX_VALUE - chargeAmount;
    }
}
