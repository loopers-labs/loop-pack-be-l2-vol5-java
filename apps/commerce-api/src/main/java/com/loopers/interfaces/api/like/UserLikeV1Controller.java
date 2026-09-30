package com.loopers.interfaces.api.like;

import com.loopers.application.like.LikeFacade;
import com.loopers.application.like.LikeListInfo;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/users/{userId}/likes")
public class UserLikeV1Controller implements UserLikeV1ApiSpec {

    private final LikeFacade likeFacade;

    @GetMapping
    @Override
    public ApiResponse<UserLikeV1Dto.LikeListResponse> getMyLikes(
        @RequestHeader("X-USER-ID") Long requesterId,
        @PathVariable(value = "userId") Long userId
    ) {
        LikeListInfo info = likeFacade.getMyLikes(requesterId, userId);
        return ApiResponse.success(UserLikeV1Dto.LikeListResponse.from(info));
    }
}
