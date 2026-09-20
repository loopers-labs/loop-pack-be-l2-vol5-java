package com.loopers.interfaces.api.productlike;

import com.loopers.application.productlike.ProductLikeFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import com.loopers.interfaces.api.auth.RequesterId;
import com.loopers.support.paging.PageQuery;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
public class ProductLikeV1Controller implements ProductLikeV1ApiSpec {

    private final ProductLikeFacade productLikeFacade;

    @PostMapping("/api/v1/products/{productId}/likes")
    @Override
    public ApiResponse<Object> like(
        @RequesterId Long requesterId,
        @PathVariable("productId") Long productId
    ) {
        productLikeFacade.like(requesterId, productId);
        return ApiResponse.success();
    }

    @DeleteMapping("/api/v1/products/{productId}/likes")
    @Override
    public ApiResponse<Object> unlike(
        @RequesterId Long requesterId,
        @PathVariable("productId") Long productId
    ) {
        productLikeFacade.unlike(requesterId, productId);
        return ApiResponse.success();
    }

    @GetMapping("/api/v1/users/{userId}/likes")
    @Override
    public ApiResponse<PageResponse<ProductLikeV1Dto.LikeResponse>> listMyLikes(
        @RequesterId Long requesterId,
        @PathVariable("userId") Long userId,
        @ParameterObject PageQuery pageQuery
    ) {
        var result = productLikeFacade.listMyLikes(requesterId, userId, pageQuery);
        return ApiResponse.success(PageResponse.from(result, ProductLikeV1Dto.LikeResponse::from));
    }
}
