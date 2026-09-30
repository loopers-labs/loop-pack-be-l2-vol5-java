package com.loopers.interfaces.api.like;

import com.loopers.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Like V1 API", description = "Loopers 좋아요 API 입니다.")
public interface LikeV1ApiSpec {

    @Operation(
        summary = "상품 좋아요 등록",
        description = "상품에 좋아요를 등록합니다. 이미 등록된 상품이면 409를 반환합니다."
    )
    ApiResponse<Object> likeProduct(
        @Schema(name = "X-USER-ID", description = "요청자 ID")
        Long userId,
        @Schema(name = "상품 ID", description = "좋아요할 상품의 ID")
        Long productId
    );

    @Operation(
        summary = "상품 좋아요 취소",
        description = "상품에 등록한 좋아요를 취소합니다. 등록된 관계가 없어도 멱등하게 200을 반환합니다."
    )
    ApiResponse<Object> unlikeProduct(
        @Schema(name = "X-USER-ID", description = "요청자 ID")
        Long userId,
        @Schema(name = "상품 ID", description = "좋아요를 취소할 상품의 ID")
        Long productId
    );
}
