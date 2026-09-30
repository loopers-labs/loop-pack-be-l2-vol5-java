package com.loopers.interfaces.api.point;

import com.loopers.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Point V1 API", description = "Loopers 포인트 API 입니다.")
public interface PointV1ApiSpec {

    @Operation(
        summary = "포인트 충전",
        description = "요청자의 포인트를 충전합니다. 충전 금액은 양수여야 합니다."
    )
    ApiResponse<PointV1Dto.PointResponse> chargePoint(
        @Schema(name = "X-USER-ID", description = "요청자 ID")
        Long userId,
        @Schema(name = "충전 요청", description = "충전할 금액")
        PointV1Dto.ChargeRequest request
    );

    @Operation(
        summary = "포인트 잔액 조회",
        description = "요청자의 포인트 잔액을 조회합니다."
    )
    ApiResponse<PointV1Dto.PointResponse> getBalance(
        @Schema(name = "X-USER-ID", description = "요청자 ID")
        Long userId
    );
}
