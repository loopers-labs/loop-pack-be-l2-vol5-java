package com.loopers.interfaces.api.point;

import com.loopers.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Point V1 API", description = "포인트 충전·잔액 조회 API 입니다.")
public interface PointV1ApiSpec {

    @Operation(
        summary = "포인트 충전",
        description = "요청 사용자의 잔액에 충전액을 더하고 충전 후 잔액을 반환합니다."
    )
    ApiResponse<PointV1Dto.PointResponse> charge(
        @Schema(name = "사용자 ID", description = "X-USER-ID 헤더로 전달하는 사용자 ID")
        Long userId,
        PointV1Dto.ChargeRequest request
    );

    @Operation(
        summary = "잔액 조회",
        description = "요청 사용자의 저장된 잔액을 조회합니다."
    )
    ApiResponse<PointV1Dto.PointResponse> getPoint(
        @Schema(name = "사용자 ID", description = "X-USER-ID 헤더로 전달하는 사용자 ID")
        Long userId
    );
}
