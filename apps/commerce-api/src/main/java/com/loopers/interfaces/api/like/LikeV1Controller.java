package com.loopers.interfaces.api.like;

import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
import com.loopers.domain.product.ProductViewQuery;
import com.loopers.application.like.LikeFacade;
import com.loopers.domain.common.PageNumber;
import com.loopers.domain.common.PageSize;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.product.ProductV1Dto;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class LikeV1Controller {

    private final LikeFacade likeFacade;
    private final ProductViewQuery productViewQuery;

    @PostMapping("/api/v1/products/{productId}/likes")
    public ApiResponse<LikeV1Dto.LikeResponse> like(
        @RequestHeader("X-USER-ID") Long userId,
        @PathVariable Long productId
    ) {
        return ApiResponse.success(
            LikeV1Dto.LikeResponse.of(productId, likeFacade.like(userId, productId), true));
    }

    @DeleteMapping("/api/v1/products/{productId}/likes")
    public ApiResponse<LikeV1Dto.LikeResponse> unlike(
        @RequestHeader("X-USER-ID") Long userId,
        @PathVariable Long productId
    ) {
        return ApiResponse.success(
            LikeV1Dto.LikeResponse.of(productId, likeFacade.unlike(userId, productId), false));
    }

    @GetMapping("/api/v1/users/{userId}/likes")
    public ApiResponse<List<ProductV1Dto.ProductResponse>> myLikes(
        @RequestHeader("X-USER-ID") Long requesterId,
        @PathVariable Long userId,
        @RequestParam(defaultValue = "0") PageNumber page,
        @RequestParam(defaultValue = "20") PageSize size
    ) {
        if (!requesterId.equals(userId)) {
            throw new DomainException(DomainError.LIKE_LIST_NOT_FOUND);
        }
        return ApiResponse.success(
            productViewQuery.findLikedBy(userId, page, size).stream().map(ProductV1Dto.ProductResponse::from).toList());
    }
}
