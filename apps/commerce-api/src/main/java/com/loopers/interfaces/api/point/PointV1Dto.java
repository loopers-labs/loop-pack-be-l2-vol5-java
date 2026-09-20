package com.loopers.interfaces.api.point;

import com.loopers.application.point.PointInfo;

public class PointV1Dto {
    /** EP-07/09 요청. 검증은 Model (ER-09). */
    public record AmountRequest(Long amount) {
    }

    /** 설계 4-3-0 PointBalance. */
    public record PointResponse(Long userId, Long balance) {
        public static PointResponse from(PointInfo info) {
            return new PointResponse(info.userId(), info.balance());
        }
    }
}
