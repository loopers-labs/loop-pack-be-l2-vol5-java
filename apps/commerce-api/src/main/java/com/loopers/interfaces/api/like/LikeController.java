package com.loopers.interfaces.api.like;

import com.loopers.application.like.LikeFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import com.loopers.interfaces.api.product.ProductDto;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1")
public class LikeController implements LikeApiSpec {

    private static final String USER_ID_HEADER = "X-USER-ID";

    private final LikeFacade likeFacade;

    @PostMapping("/products/{productId}/likes")
    @Override
    public ApiResponse<LikeDto.LikeResponse> like(
        @RequestHeader(USER_ID_HEADER) Long userId,
        @PathVariable(value = "productId") Long productId
    ) {
        return ApiResponse.success(LikeDto.LikeResponse.from(likeFacade.like(userId, productId)));
    }

    @DeleteMapping("/products/{productId}/likes")
    @Override
    public ApiResponse<LikeDto.LikeResponse> unlike(
        @RequestHeader(USER_ID_HEADER) Long userId,
        @PathVariable(value = "productId") Long productId
    ) {
        return ApiResponse.success(LikeDto.LikeResponse.from(likeFacade.unlike(userId, productId)));
    }

    @GetMapping("/users/{userId}/likes")
    @Override
    public ApiResponse<PageResponse<ProductDto.ProductResponse>> getLikedProducts(
        @RequestHeader(USER_ID_HEADER) Long requesterId,
        @PathVariable(value = "userId") Long userId,
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        return ApiResponse.success(PageResponse.from(likeFacade.getLikedProducts(requesterId, userId, page, size), ProductDto.ProductResponse::from));
    }
}
