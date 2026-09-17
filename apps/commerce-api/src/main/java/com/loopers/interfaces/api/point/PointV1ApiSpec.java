package com.loopers.interfaces.api.point;

import com.loopers.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Point V1 API", description = "포인트 고객 API")
public interface PointV1ApiSpec {

    /** EP-07 POST /api/v1/points/charge — FR-POINT-01 */
    @Operation(summary = "포인트 충전", description = "amount 를 잔액에 더하고 충전 후 잔액을 반환한다.")
    ApiResponse<PointV1Dto.PointResponse> charge(Long requesterId, PointV1Dto.AmountRequest request);

    /** EP-08 GET /api/v1/points — FR-POINT-02 */
    @Operation(summary = "내 잔액 조회")
    ApiResponse<PointV1Dto.PointResponse> getBalance(Long requesterId);

    /** EP-09 POST /api/v1/points/refund — FR-POINT-03 [추가] */
    @Operation(summary = "포인트 환불", description = "amount 를 잔액에서 빼고 환불 후 잔액을 반환한다. 실제 돈의 이동은 없다.")
    ApiResponse<PointV1Dto.PointResponse> refund(Long requesterId, PointV1Dto.AmountRequest request);
}
