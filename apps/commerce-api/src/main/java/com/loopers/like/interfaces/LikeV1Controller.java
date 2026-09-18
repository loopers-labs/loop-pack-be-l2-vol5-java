package com.loopers.like.interfaces;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.ListResponse;
import com.loopers.interfaces.api.PageQuery;
import com.loopers.interfaces.api.Requester;
import com.loopers.like.application.LikeUseCase;
import com.loopers.product.interfaces.ProductV1Dto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1")
public class LikeV1Controller {

    private final LikeUseCase likeUseCase;

    /**
     * 처음 등록하면 201, 이미 좋아요한 상품이면 새로 만들지 않고 200이다.
     */
    @PostMapping("/products/{productId}/likes")
    public ResponseEntity<ApiResponse<Object>> register(
        Requester requester,
        @PathVariable Long productId
    ) {
        boolean created = likeUseCase.register(requester.userId(), productId);
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK)
            .body(ApiResponse.success());
    }

    @DeleteMapping("/products/{productId}/likes")
    public ApiResponse<Object> cancel(Requester requester, @PathVariable Long productId) {
        likeUseCase.cancel(requester.userId(), productId);
        return ApiResponse.success();
    }

    @GetMapping("/users/{userId}/likes")
    public ApiResponse<ListResponse<ProductV1Dto.CustomerProductResponse>> getMyLikes(
        Requester requester,
        @PathVariable Long userId,
        @RequestParam(defaultValue = PageQuery.DEFAULT_PAGE) int page,
        @RequestParam(defaultValue = PageQuery.DEFAULT_SIZE) int size
    ) {
        PageQuery query = new PageQuery(page, size);
        return ApiResponse.success(ListResponse.from(
            likeUseCase.findMinePage(requester.userId(), userId, query.page(), query.size()),
            ProductV1Dto.CustomerProductResponse::from
        ));
    }
}
