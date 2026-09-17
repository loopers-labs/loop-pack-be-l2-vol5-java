package com.loopers.interfaces.api.like;

import com.loopers.application.like.LikeFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/users/{userId}/likes")
public class MyLikeV1Controller {

    private final LikeFacade likeFacade;

    @GetMapping
    public ApiResponse<List<LikeV1Dto.MyLikeProductResponse>> getMyLikes(
        @RequestHeader(value = "X-USER-ID", required = false) Long requesterId,
        @PathVariable Long userId
    ) {
        if (requesterId == null || !requesterId.equals(userId)) {
            throw new CoreException(ErrorType.BAD_REQUEST, "요청자 식별값이 올바르지 않습니다.");
        }
        return ApiResponse.success(likeFacade.getMyLikes(userId).stream()
            .map(LikeV1Dto.MyLikeProductResponse::from)
            .toList());
    }
}
