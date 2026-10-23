package com.loopers.interfaces.api.point;

import com.loopers.application.point.dto.PointInfo;
import jakarta.validation.constraints.NotNull;

public class PointDto {
    // 누락(@NotNull)은 요청 형식 오류라 여기서 거절한다. 0 이하 같은 도메인 규칙은 Money·PointBalance 가 판단한다.
    public record ChargeRequest(@NotNull Long amount) {}

    public record PointResponse(long balance) {
        public static PointResponse from(PointInfo info) {
            return new PointResponse(info.balance());
        }
    }
}
