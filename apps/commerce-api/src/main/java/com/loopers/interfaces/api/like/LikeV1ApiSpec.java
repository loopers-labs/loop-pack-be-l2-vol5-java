package com.loopers.interfaces.api.like;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import com.loopers.interfaces.api.product.ProductV1Dto;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Like V1 API", description = "고객 좋아요 API")
public interface LikeV1ApiSpec {

    ResponseEntity<ApiResponse<LikeV1Dto.LikeResponse>> like(Long userId, Long productId);

    ApiResponse<Object> cancel(Long userId, Long productId);

    ApiResponse<PageResponse<ProductV1Dto.ProductResponse>> getMyLikes(
        Long userId, Long pathUserId, Integer page, Integer size, String sort);
}
