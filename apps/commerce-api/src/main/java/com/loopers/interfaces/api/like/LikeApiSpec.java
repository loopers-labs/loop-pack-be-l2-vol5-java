package com.loopers.interfaces.api.like;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import com.loopers.interfaces.api.product.ProductDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Like API", description = "고객용 좋아요 API 입니다.")
public interface LikeApiSpec {

    @Operation(
        summary = "좋아요 등록",
        description = "상품에 좋아요합니다. 이미 좋아요한 상품이면 같은 결과를 반환합니다."
    )
    ApiResponse<LikeDto.LikeResponse> like(
        @Schema(name = "요청자 ID", description = "X-USER-ID 헤더")
        Long userId,
        @Schema(name = "상품 ID", description = "좋아요할 상품의 ID")
        Long productId
    );

    @Operation(
        summary = "좋아요 취소",
        description = "자신의 좋아요를 취소합니다. 상품이 삭제되었어도 취소할 수 있습니다."
    )
    ApiResponse<LikeDto.LikeResponse> unlike(
        @Schema(name = "요청자 ID", description = "X-USER-ID 헤더")
        Long userId,
        @Schema(name = "상품 ID", description = "좋아요를 취소할 상품의 ID")
        Long productId
    );

    @Operation(
        summary = "내 좋아요 목록 조회",
        description = "자신이 좋아요한 상품 중 삭제되지 않은 상품을 최근 좋아요순으로 조회합니다."
    )
    ApiResponse<PageResponse<ProductDto.ProductResponse>> getLikedProducts(
        @Schema(name = "요청자 ID", description = "X-USER-ID 헤더")
        Long requesterId,
        @Schema(name = "사용자 ID", description = "요청자와 같아야 합니다")
        Long userId,
        @Schema(name = "페이지", description = "0부터 시작 (기본 0)")
        int page,
        @Schema(name = "페이지 크기", description = "1~100 (기본 20)")
        int size
    );
}
