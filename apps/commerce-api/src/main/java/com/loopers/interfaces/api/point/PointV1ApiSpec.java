package com.loopers.interfaces.api.point;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Point V1 API", description = "고객용 포인트 API (X-USER-ID 필요, 1포인트 = 1원)")
public interface PointV1ApiSpec {

    @Operation(summary = "포인트 충전", description = "양의 정수 금액을 충전하고 충전 후 사용 가능 잔액을 돌려줍니다.")
    ApiResponse<PointV1Dto.BalanceResponse> charge(@Parameter(hidden = true) LoginUser loginUser, PointV1Dto.ChargeRequest request);

    @Operation(summary = "잔액 조회", description = "만료되지 않은 포인트 그룹의 남은 금액 합을 돌려줍니다.")
    ApiResponse<PointV1Dto.BalanceResponse> getBalance(@Parameter(hidden = true) LoginUser loginUser);
}
