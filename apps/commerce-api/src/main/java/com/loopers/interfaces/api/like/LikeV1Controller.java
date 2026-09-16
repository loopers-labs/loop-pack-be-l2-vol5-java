package com.loopers.interfaces.api.like;

import com.loopers.application.like.LikeFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
public class LikeV1Controller {

    private static final String USER_ID_HEADER = "X-USER-ID";

    private final LikeFacade likeFacade;

    @PostMapping("/api/v1/products/{productId}/likes")
    public ApiResponse<LikeV1Dto.LikeResponse> like(
        @RequestHeader(value = USER_ID_HEADER, required = false) Long userId,
        @PathVariable(value = "productId") Long productId
    ) {
        return ApiResponse.success(LikeV1Dto.LikeResponse.from(likeFacade.like(requireUserId(userId), productId)));
    }

    @DeleteMapping("/api/v1/products/{productId}/likes")
    public ApiResponse<LikeV1Dto.LikeResponse> unlike(
        @RequestHeader(value = USER_ID_HEADER, required = false) Long userId,
        @PathVariable(value = "productId") Long productId
    ) {
        return ApiResponse.success(LikeV1Dto.LikeResponse.from(likeFacade.unlike(requireUserId(userId), productId)));
    }

    @GetMapping("/api/v1/users/{userId}/likes")
    public ApiResponse<LikeV1Dto.MyLikesResponse> getMyLikes(
        @RequestHeader(value = USER_ID_HEADER, required = false) Long requesterId,
        @PathVariable(value = "userId") Long pathUserId
    ) {
        return ApiResponse.success(
            LikeV1Dto.MyLikesResponse.from(likeFacade.getMyLikes(requireUserId(requesterId), pathUserId))
        );
    }

    private Long requireUserId(Long userId) {
        if (userId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, USER_ID_HEADER + " 헤더가 필요합니다.");
        }
        return userId;
    }
}
