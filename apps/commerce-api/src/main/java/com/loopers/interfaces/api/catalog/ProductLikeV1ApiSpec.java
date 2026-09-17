package com.loopers.interfaces.api.catalog;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Product Like V1 API", description = "좋아요 고객 API")
public interface ProductLikeV1ApiSpec {

    /** EP-04 POST /api/v1/products/{productId}/likes — FR-LIKE-01 */
    @Operation(summary = "좋아요 등록", description = "사용자–상품 관계를 저장한다. 이미 있으면 변화 없이 성공.")
    ApiResponse<Object> like(Long requesterId, Long productId);

    /** EP-05 DELETE /api/v1/products/{productId}/likes — FR-LIKE-02 */
    @Operation(summary = "좋아요 취소", description = "사용자–상품 관계를 삭제한다. 없으면 변화 없이 성공.")
    ApiResponse<Object> unlike(Long requesterId, Long productId);

    /** EP-06 GET /api/v1/users/{userId}/likes — FR-LIKE-03 */
    @Operation(summary = "내 좋아요 목록", description = "요청자의 좋아요 중 상품이 삭제되지 않은 것만 등록 최신순으로 반환한다.")
    ApiResponse<PageResponse<ProductLikeV1Dto.LikeResponse>> listMyLikes(Long requesterId, Long userId, Integer page, Integer size);
}
