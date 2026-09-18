package com.loopers.interfaces.api.like;

import com.loopers.application.like.DeleteLikeFacade;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;

@RestController
@RequiredArgsConstructor
public class DeleteLikeController {
    private final DeleteLikeFacade facade;
    @DeleteMapping("/api/v1/products/{productId}/likes")
    public ApiResponse<Object> delete(@RequestHeader(value = "X-USER-ID", required = false) Long userId, @PathVariable long productId) {
        facade.delete(userId, productId);
        return ApiResponse.success();
    }
}
