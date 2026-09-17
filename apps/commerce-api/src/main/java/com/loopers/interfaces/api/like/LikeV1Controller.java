package com.loopers.interfaces.api.like;

import com.loopers.application.like.LikeFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.support.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
public class LikeV1Controller implements LikeV1ApiSpec {

    private final LikeFacade likeFacade;

    @PostMapping("/api/v1/products/{productId}/likes")
    @Override
    public ApiResponse<LikeV1Dto.LikeResponse> like(LoginUser loginUser, @PathVariable Long productId) {
        likeFacade.like(loginUser.id(), productId);
        return ApiResponse.success(new LikeV1Dto.LikeResponse(productId, true));
    }

    @DeleteMapping("/api/v1/products/{productId}/likes")
    @Override
    public ApiResponse<LikeV1Dto.LikeResponse> unlike(LoginUser loginUser, @PathVariable Long productId) {
        likeFacade.unlike(loginUser.id(), productId);
        return ApiResponse.success(new LikeV1Dto.LikeResponse(productId, false));
    }

    @GetMapping("/api/v1/users/{userId}/likes")
    @Override
    public ApiResponse<List<LikeV1Dto.LikedProductResponse>> getLikedProducts(LoginUser loginUser, @PathVariable Long userId) {
        return ApiResponse.success(
            likeFacade.getLikedProducts(loginUser.id(), userId).stream()
                .map(LikeV1Dto.LikedProductResponse::from)
                .toList()
        );
    }
}
