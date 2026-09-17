package com.loopers.interfaces.api.point;

import com.loopers.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Point API", description = "고객용 포인트 API 입니다.")
public interface PointApiSpec {

    @Operation(
        summary = "내 포인트 충전",
        description = "양의 정수 금액을 잔액에 더하고 충전 후 잔액을 반환합니다. 1포인트는 1원입니다."
    )
    ApiResponse<PointDto.BalanceResponse> charge(
        @Schema(name = "요청자 ID", description = "X-USER-ID 헤더")
        Long userId,
        PointDto.ChargeRequest request
    );

    @Operation(
        summary = "내 포인트 잔액 조회",
        description = "충전 이력이 없으면 0을 반환합니다."
    )
    ApiResponse<PointDto.BalanceResponse> getBalance(
        @Schema(name = "요청자 ID", description = "X-USER-ID 헤더")
        Long userId
    );
}
