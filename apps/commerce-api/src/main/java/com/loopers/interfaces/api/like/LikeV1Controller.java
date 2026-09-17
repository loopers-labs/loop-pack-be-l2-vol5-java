package com.loopers.interfaces.api.like;

import com.loopers.application.like.LikeFacade;
import com.loopers.application.like.LikeInfo;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/products/{productId}/likes")
public class LikeV1Controller implements LikeV1ApiSpec {

    private final LikeFacade likeFacade;

    @PostMapping
    @Override
    public ApiResponse<LikeV1Dto.LikeResponse> add(
        @RequestHeader(value = "X-USER-ID", required = false) Long userId,
        @PathVariable Long productId
    ) {
        validateUserId(userId);
        LikeInfo info = likeFacade.add(userId, productId);
        return ApiResponse.success(LikeV1Dto.LikeResponse.from(info));
    }

    @DeleteMapping
    @Override
    public ApiResponse<LikeV1Dto.LikeResponse> cancel(
        @RequestHeader(value = "X-USER-ID", required = false) Long userId,
        @PathVariable Long productId
    ) {
        validateUserId(userId);
        LikeInfo info = likeFacade.cancel(userId, productId);
        return ApiResponse.success(LikeV1Dto.LikeResponse.from(info));
    }

    private void validateUserId(Long userId) {
        if (userId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "요청자 식별값이 필요합니다.");
        }
    }
}
