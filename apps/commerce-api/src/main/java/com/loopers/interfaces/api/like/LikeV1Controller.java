package com.loopers.interfaces.api.like;

import com.loopers.domain.common.ListSort;
import com.loopers.domain.common.PageCommand;
import com.loopers.domain.common.PageResult;
import com.loopers.domain.like.LikeModel;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.ProductQueryResult;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import com.loopers.interfaces.api.product.ProductV1Dto;
import com.loopers.interfaces.api.support.CustomerId;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
public class LikeV1Controller implements LikeV1ApiSpec {

    private final LikeService likeService;

    @PostMapping("/api/v1/products/{productId}/likes")
    @Override
    public ResponseEntity<ApiResponse<LikeV1Dto.LikeResponse>> like(
        @CustomerId Long userId,
        @PathVariable(value = "productId") Long productId
    ) {
        LikeModel created = likeService.like(userId, productId);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success(LikeV1Dto.LikeResponse.from(created)));
    }

    @DeleteMapping("/api/v1/products/{productId}/likes")
    @Override
    public ApiResponse<Object> cancel(
        @CustomerId Long userId,
        @PathVariable(value = "productId") Long productId
    ) {
        likeService.cancel(userId, productId);
        return ApiResponse.success();
    }

    /**
     * 경로의 사용자 ID 는 조회 대상이다. 요청자와 다르면 다른 사용자의 관계를 노출하지 않는다.
     */
    @GetMapping("/api/v1/users/{userId}/likes")
    @Override
    public ApiResponse<PageResponse<ProductV1Dto.ProductResponse>> getMyLikes(
        @CustomerId Long userId,
        @PathVariable(value = "userId") Long pathUserId,
        @RequestParam(value = "page", required = false) Integer page,
        @RequestParam(value = "size", required = false) Integer size,
        @RequestParam(value = "sort", required = false) String sort
    ) {
        if (!userId.equals(pathUserId)) {
            throw new CoreException(ErrorType.USER_NOT_FOUND);
        }

        PageResult<ProductQueryResult> result =
            likeService.getMyLikedProducts(userId, PageCommand.of(page, size), ListSort.from(sort));
        return ApiResponse.success(PageResponse.of(result, ProductV1Dto.ProductResponse::from));
    }
}
