package com.loopers.interfaces.api.like;

import com.loopers.application.like.LikeFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageQuery;
import com.loopers.interfaces.api.PageResponse;
import com.loopers.interfaces.api.auth.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
public class LikeV1Controller implements LikeV1ApiSpec {

    private final LikeFacade likeFacade;

    @PostMapping("/api/v1/products/{productId}/likes")
    @Override
    public ApiResponse<LikeV1Dto.LikeResponse> like(LoginUser loginUser, @PathVariable Long productId) {
        return ApiResponse.success(LikeV1Dto.LikeResponse.from(likeFacade.like(loginUser.id(), productId)));
    }

    @DeleteMapping("/api/v1/products/{productId}/likes")
    @Override
    public ApiResponse<LikeV1Dto.LikeResponse> unlike(LoginUser loginUser, @PathVariable Long productId) {
        return ApiResponse.success(LikeV1Dto.LikeResponse.from(likeFacade.unlike(loginUser.id(), productId)));
    }

    @GetMapping("/api/v1/users/{userId}/likes")
    @Override
    public ApiResponse<PageResponse<LikeV1Dto.LikedProductResponse>> getMyLikes(
        LoginUser loginUser,
        @PathVariable Long userId,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.success(PageResponse.from(
            likeFacade.getMyLikes(loginUser.id(), userId, PageQuery.of(page, size)),
            LikeV1Dto.LikedProductResponse::from
        ));
    }
}
