package com.loopers.interfaces.api.point;

import com.loopers.application.point.PointInfo;

public class PointV1Dto {
    public record BalanceResponse(long balance) {
        public static BalanceResponse from(PointInfo info) {
            return new BalanceResponse(info.balance());
        }
    }

    public record ChargeRequest(Long amount) {}

    public record ChargeResponse(long balance) {
        public static ChargeResponse from(PointInfo info) {
            return new ChargeResponse(info.balance());
        }
    }
}
