package com.loopers.interfaces.api.like;

import com.loopers.application.like.LikeFacade;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/products/{productId}/likes")
public class LikeV1Controller implements LikeV1ApiSpec {

    private final LikeFacade likeFacade;

    @PostMapping
    @Override
    public ApiResponse<Object> likeProduct(
        @RequestHeader("X-USER-ID") Long userId,
        @PathVariable(value = "productId") Long productId
    ) {
        likeFacade.likeProduct(userId, productId);
        return ApiResponse.success();
    }

    @DeleteMapping
    @Override
    public ApiResponse<Object> unlikeProduct(
        @RequestHeader("X-USER-ID") Long userId,
        @PathVariable(value = "productId") Long productId
    ) {
        likeFacade.unlikeProduct(userId, productId);
        return ApiResponse.success();
    }
}
