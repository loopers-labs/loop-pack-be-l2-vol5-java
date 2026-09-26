package com.loopers.interfaces.api.point;

import com.loopers.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Point V1 API", description = "포인트 API입니다.")
public interface PointV1ApiSpec {

    @Operation(summary = "포인트 잔액 조회", description = "요청자의 포인트 잔액을 조회합니다.")
    ApiResponse<PointV1Dto.BalanceResponse> getBalance(Long userId);

    @Operation(summary = "포인트 충전", description = "요청자의 포인트를 충전합니다.")
    ApiResponse<PointV1Dto.ChargeResponse> charge(
        Long userId,
        PointV1Dto.ChargeRequest request
    );
}
