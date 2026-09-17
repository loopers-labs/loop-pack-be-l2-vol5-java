package com.loopers.interfaces.api.point;

import com.loopers.application.point.PointInfo;

public class PointDto {
    public record ChargeRequest(Long amount) {}

    public record BalanceResponse(Long balance) {
        public static BalanceResponse from(PointInfo info) {
            return new BalanceResponse(info.balance());
        }
    }
}
