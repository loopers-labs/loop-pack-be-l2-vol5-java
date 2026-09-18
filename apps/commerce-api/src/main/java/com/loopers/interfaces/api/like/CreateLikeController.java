package com.loopers.interfaces.api.like;

import com.loopers.application.like.CreateLikeFacade;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;

@RestController
@RequiredArgsConstructor
public class CreateLikeController {
    private final CreateLikeFacade facade;
    @PostMapping("/api/v1/products/{productId}/likes")
    public ApiResponse<Object> create(@RequestHeader(value = "X-USER-ID", required = false) Long userId, @PathVariable long productId) {
        facade.create(userId, productId);
        return ApiResponse.success();
    }
}
