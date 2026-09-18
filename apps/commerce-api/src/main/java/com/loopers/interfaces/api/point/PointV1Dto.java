package com.loopers.interfaces.api.point;

import com.loopers.domain.common.Money;
import com.loopers.domain.point.ChargeAmount;

public class PointV1Dto {

    public record ChargeRequest(Long amount) {
        public ChargeRequest {
            if (amount == null) {
                throw new IllegalArgumentException("amount 는 필수입니다.");
            }
            if (amount <= 0) {
                throw new IllegalArgumentException("amount 는 양의 정수여야 합니다: " + amount);
            }
        }

        public ChargeAmount toAmount() {
            return ChargeAmount.of(amount);
        }
    }

    public record BalanceResponse(long balance) {
        public static BalanceResponse from(Money balance) {
            return new BalanceResponse(balance.amount());
        }
    }
}
