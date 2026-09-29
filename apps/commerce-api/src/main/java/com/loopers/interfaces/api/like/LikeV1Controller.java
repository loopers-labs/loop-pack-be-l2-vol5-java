package com.loopers.interfaces.api.like;

import com.loopers.application.like.LikeFacade;
import com.loopers.application.like.LikeInfo;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
public class LikeV1Controller {

    private static final String USER_ID_HEADER = "X-USER-ID";

    private final LikeFacade likeFacade;

    @PostMapping("/api/v1/products/{productId}/likes")
    public ResponseEntity<ApiResponse<LikeV1Dto.LikeResponse>> like(
        @RequestHeader(value = USER_ID_HEADER, required = false) Long userId,
        @PathVariable(value = "productId") Long productId
    ) {
        boolean created = likeFacade.like(userId, productId);
        ApiResponse<LikeV1Dto.LikeResponse> response =
            ApiResponse.success(new LikeV1Dto.LikeResponse(productId, created));

        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK).body(response);
    }

    @DeleteMapping("/api/v1/products/{productId}/likes")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlike(
        @RequestHeader(value = USER_ID_HEADER, required = false) Long userId,
        @PathVariable(value = "productId") Long productId
    ) {
        likeFacade.unlike(userId, productId);
    }

    @GetMapping("/api/v1/users/{userId}/likes")
    public ApiResponse<List<LikeV1Dto.LikedProductResponse>> getMyLikes(
        @RequestHeader(value = USER_ID_HEADER, required = false) Long requesterId,
        @PathVariable(value = "userId") Long userId
    ) {
        List<LikeInfo> infos = likeFacade.getMyLikes(requesterId, userId);
        List<LikeV1Dto.LikedProductResponse> response = infos.stream()
            .map(LikeV1Dto.LikedProductResponse::from)
            .toList();

        return ApiResponse.success(response);
    }
}
