package com.loopers.interfaces.api.like;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import com.loopers.interfaces.api.auth.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Like V1 API", description = "상품 좋아요 API 입니다. X-USER-ID 헤더로 요청자를 식별합니다.")
public interface LikeV1ApiSpec {

    @Operation(summary = "좋아요 등록", description = "이미 누른 상품이면 그대로 성공합니다. 없거나 삭제된 상품은 PRODUCT_NOT_FOUND 입니다.")
    ApiResponse<LikeV1Dto.LikeResponse> like(
        @Parameter(hidden = true) LoginUser loginUser,
        @Schema(description = "상품 ID") Long productId
    );

    @Operation(summary = "좋아요 취소", description = "자신의 관계만 지웁니다. 관계가 없거나 상품이 삭제되었어도 성공합니다.")
    ApiResponse<LikeV1Dto.LikeResponse> unlike(
        @Parameter(hidden = true) LoginUser loginUser,
        @Schema(description = "상품 ID") Long productId
    );

    @Operation(
        summary = "내 좋아요 목록",
        description = "최근에 누른 순입니다. 삭제된 상품은 제외합니다. 경로의 userId 가 요청자 본인이 아니면 USER_NOT_FOUND 입니다."
    )
    ApiResponse<PageResponse<LikeV1Dto.LikedProductResponse>> getMyLikes(
        @Parameter(hidden = true) LoginUser loginUser,
        @Schema(description = "사용자 ID") Long userId,
        @Schema(description = "페이지 (1부터)") int page,
        @Schema(description = "페이지 크기 (최대 100)") int size
    );
}
