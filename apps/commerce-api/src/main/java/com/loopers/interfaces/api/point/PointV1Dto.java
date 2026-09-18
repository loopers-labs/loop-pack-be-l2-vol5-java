package com.loopers.interfaces.api.point;

import com.loopers.application.point.PointInfo;

public class PointV1Dto {
    /**
     * 충전액은 원시 타입이라 누락 · 문자 · 표현 범위 초과는 역직렬화에서 BAD_REQUEST 로 거절된다.
     * 0 이하는 여기서 검사하지 않고 규칙의 주인인 Point 가 거절한다 (설계 D-25).
     */
    public record ChargeRequest(long amount) {}

    public record PointResponse(long balance) {
        public static PointResponse from(PointInfo info) {
            return new PointResponse(info.balance());
        }
    }
}
