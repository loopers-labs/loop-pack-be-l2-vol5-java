package com.loopers.interfaces.api.catalog;

import com.loopers.application.catalog.ProductLikeFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import com.loopers.interfaces.api.auth.RequesterId;
import com.loopers.support.paging.PageQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
        @RequestParam(value = "page", required = false) Integer page,
        @RequestParam(value = "size", required = false) Integer size
    ) {
        var result = productLikeFacade.listMyLikes(requesterId, userId, PageQuery.of(page, size));
        return ApiResponse.success(PageResponse.from(result, ProductLikeV1Dto.LikeResponse::from));
    }
}
