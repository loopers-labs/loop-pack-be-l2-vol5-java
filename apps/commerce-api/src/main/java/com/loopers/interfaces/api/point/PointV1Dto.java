package com.loopers.interfaces.api.point;

import com.loopers.application.point.PointBalanceInfo;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

public class PointV1Dto {

    public record ChargeRequest(Long amount) {
        public long requiredAmount() {
            if (amount == null) {
                throw new CoreException(ErrorType.BAD_REQUEST, "필수 필드 'amount'이(가) 누락되었습니다.");
            }
            return amount;
        }
    }

    public record BalanceResponse(long balance) {
        public static BalanceResponse from(PointBalanceInfo info) {
            return new BalanceResponse(info.balance());
        }
    }
}
