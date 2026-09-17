package com.loopers.interfaces.api.point;

import com.loopers.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Point V1 API", description = "고객 포인트 API")
public interface PointV1ApiSpec {

    ApiResponse<PointV1Dto.PointResponse> charge(Long userId, PointV1Dto.PointChargeRequest request);

    ApiResponse<PointV1Dto.PointResponse> getBalance(Long userId);
}
