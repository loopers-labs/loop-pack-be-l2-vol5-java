package com.loopers.interfaces.api.like;

import com.loopers.interfaces.api.ApiResponse;

public interface LikeV1ApiSpec {

    ApiResponse<LikeV1Dto.LikeResponse> add(Long userId, Long productId);

    ApiResponse<LikeV1Dto.LikeResponse> cancel(Long userId, Long productId);
}
