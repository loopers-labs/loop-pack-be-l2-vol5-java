package com.loopers.interfaces.api.point;

import com.loopers.application.user.PointInfo;

public class PointV1Dto {
    public record ChargeRequest(long amount) {
    }

    public record PointResponse(long balance) {
        public static PointResponse from(PointInfo info) {
            return new PointResponse(info.balance());
        }
    }
}
