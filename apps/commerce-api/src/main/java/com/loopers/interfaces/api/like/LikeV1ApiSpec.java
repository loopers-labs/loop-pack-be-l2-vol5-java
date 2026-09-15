package com.loopers.interfaces.api.like;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

@Tag(name = "Like V1 API", description = "고객용 좋아요 API (X-USER-ID 필요)")
public interface LikeV1ApiSpec {

    @Operation(summary = "좋아요 등록", description = "이미 좋아요한 상품이면 그대로 성공합니다.")
    ApiResponse<LikeV1Dto.LikeResponse> like(@Parameter(hidden = true) LoginUser loginUser, Long productId);

    @Operation(summary = "좋아요 취소", description = "좋아요하지 않았거나 삭제된 상품이어도 성공합니다.")
    ApiResponse<LikeV1Dto.LikeResponse> unlike(@Parameter(hidden = true) LoginUser loginUser, Long productId);

    @Operation(summary = "내 좋아요 목록", description = "본인의 목록만 조회하며 삭제된 상품은 제외합니다.")
    ApiResponse<List<LikeV1Dto.LikedProductResponse>> getLikedProducts(@Parameter(hidden = true) LoginUser loginUser, Long userId);
}
