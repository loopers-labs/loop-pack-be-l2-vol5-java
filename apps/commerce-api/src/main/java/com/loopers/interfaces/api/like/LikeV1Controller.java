package com.loopers.interfaces.api.like;

import com.loopers.application.like.LikeFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.auth.LoginUserId;
import com.loopers.interfaces.api.product.ProductV1Dto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
public class LikeV1Controller {

    private final LikeFacade likeFacade;

    @PostMapping("/api/v1/products/{productId}/likes")
    public ApiResponse<Object> like(@PathVariable Long productId, @LoginUserId Long userId) {
        likeFacade.like(userId, productId);
        return ApiResponse.success();
    }

    @DeleteMapping("/api/v1/products/{productId}/likes")
    public ApiResponse<Object> unlike(@PathVariable Long productId, @LoginUserId Long userId) {
        likeFacade.unlike(userId, productId);
        return ApiResponse.success();
    }

    // 정식 회원가입/인증 체계가 없는 지금은 {userId} 경로 대신 "요청 헤더의 나"만 조회 가능하도록 범위를 좁힘
    @GetMapping("/api/v1/likes")
    public ApiResponse<List<ProductV1Dto.ProductResponse>> getMyLikedProducts(
        @LoginUserId Long userId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        var products = likeFacade.getMyLikedProducts(userId, PageRequest.of(page, size));
        return ApiResponse.success(products.map(ProductV1Dto.ProductResponse::from).getContent());
    }
}
