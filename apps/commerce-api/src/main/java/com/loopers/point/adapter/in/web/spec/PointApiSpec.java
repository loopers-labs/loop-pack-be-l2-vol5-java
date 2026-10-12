package com.loopers.point.adapter.in.web.spec;

import com.loopers.point.adapter.in.web.dto.PointDto;
import com.loopers.support.web.ApiResponse;
import com.loopers.user.adapter.in.web.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Point V1 API", description = "고객용 포인트 API (X-USER-ID 필요, 1포인트 = 1원)")
public interface PointApiSpec {

    @Operation(summary = "포인트 충전", description = "양의 정수 금액을 충전하고 충전 후 사용 가능 잔액을 돌려줍니다.")
    ApiResponse<PointDto.BalanceResponse> charge(@Parameter(hidden = true) LoginUser loginUser, PointDto.ChargeRequest request);

    @Operation(summary = "잔액 조회", description = "만료되지 않은 포인트 그룹의 남은 금액 합을 돌려줍니다.")
    ApiResponse<PointDto.BalanceResponse> getBalance(@Parameter(hidden = true) LoginUser loginUser);
}
