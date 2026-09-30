package com.loopers.interfaces.api.like;

import com.loopers.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "User Like V1 API", description = "Loopers 내 좋아요 목록 API 입니다.")
public interface UserLikeV1ApiSpec {

    @Operation(
        summary = "내 좋아요 목록 조회",
        description = "요청자가 좋아요한 상품 ID 목록을 조회합니다. 요청자와 userId가 다르면 404를 반환합니다(존재 비노출)."
    )
    ApiResponse<UserLikeV1Dto.LikeListResponse> getMyLikes(
        @Schema(name = "X-USER-ID", description = "요청자 ID")
        Long requesterId,
        @Schema(name = "사용자 ID", description = "좋아요 목록을 조회할 사용자 ID")
        Long userId
    );
}
