package com.loopers.like.adapter.in.web;

import com.loopers.like.adapter.in.web.dto.LikeDto;
import com.loopers.like.adapter.in.web.spec.LikeApiSpec;
import com.loopers.like.application.LikeQueryService;
import com.loopers.like.application.port.in.LikeCommandUseCase;
import com.loopers.support.web.ApiResponse;
import com.loopers.user.adapter.in.web.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
public class LikeController implements LikeApiSpec {

    private final LikeCommandUseCase likeCommandUseCase;
    private final LikeQueryService likeQueryService;

    @PostMapping("/api/v1/products/{productId}/likes")
    @Override
    public ApiResponse<LikeDto.LikeResponse> like(LoginUser loginUser, @PathVariable Long productId) {
        likeCommandUseCase.like(loginUser.id(), productId);
        return ApiResponse.success(new LikeDto.LikeResponse(productId, true));
    }

    @DeleteMapping("/api/v1/products/{productId}/likes")
    @Override
    public ApiResponse<LikeDto.LikeResponse> unlike(LoginUser loginUser, @PathVariable Long productId) {
        likeCommandUseCase.unlike(loginUser.id(), productId);
        return ApiResponse.success(new LikeDto.LikeResponse(productId, false));
    }

    @GetMapping("/api/v1/users/{userId}/likes")
    @Override
    public ApiResponse<List<LikeDto.LikedProductResponse>> getLikedProducts(LoginUser loginUser, @PathVariable Long userId) {
        return ApiResponse.success(
            likeQueryService.getLikedProducts(loginUser.id(), userId).stream()
                .map(LikeDto.LikedProductResponse::from)
                .toList()
        );
    }
}
