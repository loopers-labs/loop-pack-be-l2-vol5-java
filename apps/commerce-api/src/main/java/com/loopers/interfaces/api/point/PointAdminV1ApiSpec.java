package com.loopers.interfaces.api.point;

import com.loopers.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Point Admin V1 API", description = "포인트 관리자 API")
public interface PointAdminV1ApiSpec {

    /** EP-27 POST /api-admin/v1/points/charge — FR-ADMIN-POINT-01 [추가] */
    @Operation(summary = "사용자 포인트 충전", description = "바디 userId 의 잔액에 amount 를 더한다.")
    ApiResponse<PointV1Dto.PointResponse> charge(Long requesterId, PointAdminV1Dto.AdjustRequest request);

    /** EP-28 POST /api-admin/v1/points/deduct — FR-ADMIN-POINT-02 [추가] */
    @Operation(summary = "사용자 포인트 차감", description = "바디 userId 의 잔액에서 amount 를 뺀다. 잔액을 0 까지만 내릴 수 있다.")
    ApiResponse<PointV1Dto.PointResponse> deduct(Long requesterId, PointAdminV1Dto.AdjustRequest request);
}
