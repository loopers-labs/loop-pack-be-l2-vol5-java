package com.loopers.interfaces.api.point;

import com.loopers.domain.point.PointModel;

public class PointV1Dto {

    public record PointChargeRequest(Long amount) {
    }

    public record PointResponse(Long balance) {
        public static PointResponse of(long balance) {
            return new PointResponse(balance);
        }

        public static PointResponse from(PointModel point) {
            return new PointResponse(point.getBalance());
        }
    }
}
