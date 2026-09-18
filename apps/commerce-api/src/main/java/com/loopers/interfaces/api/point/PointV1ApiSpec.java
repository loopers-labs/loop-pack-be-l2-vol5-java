package com.loopers.interfaces.api.point;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.auth.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Point V1 API", description = "포인트 API 입니다. X-USER-ID 헤더로 요청자를 식별합니다. 1포인트는 1원입니다.")
public interface PointV1ApiSpec {

    @Operation(
        summary = "포인트 충전",
        description = "충전 후 잔액을 돌려줍니다. 0 이하는 INVALID_CHARGE_AMOUNT, 잔액이 표현 범위를 넘으면 BALANCE_LIMIT_EXCEEDED 입니다."
    )
    ApiResponse<PointV1Dto.PointResponse> charge(@Parameter(hidden = true) LoginUser loginUser, PointV1Dto.ChargeRequest request);

    @Operation(summary = "내 잔액 조회", description = "충전한 적이 없으면 0원입니다.")
    ApiResponse<PointV1Dto.PointResponse> getPoint(@Parameter(hidden = true) LoginUser loginUser);
}
