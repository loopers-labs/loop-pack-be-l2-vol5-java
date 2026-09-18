package com.loopers.interfaces.api.like;

import com.loopers.application.like.LikeInfo;
import com.loopers.application.like.LikeService;
import com.loopers.application.product.ProductInfo;
import com.loopers.domain.common.PageResult;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.commerce.StrictInput;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class LikeController {

    private final LikeService likeService;

    public LikeController(LikeService likeService) {
        this.likeService = likeService;
    }

    @PostMapping("/products/{productId}/likes")
    public ApiResponse<LikeInfo> register(
        @RequestHeader(value = "X-USER-ID", required = false) String requester,
        @PathVariable String productId
    ) {
        return ApiResponse.success(likeService.register(requester, StrictInput.id(productId)));
    }

    @DeleteMapping("/products/{productId}/likes")
    public ApiResponse<LikeInfo> cancel(
        @RequestHeader(value = "X-USER-ID", required = false) String requester,
        @PathVariable String productId
    ) {
        return ApiResponse.success(likeService.cancel(requester, StrictInput.id(productId)));
    }

    @GetMapping("/users/{userId}/likes")
    public ApiResponse<PageResult<ProductInfo>> list(
        @RequestHeader(value = "X-USER-ID", required = false) String requester,
        @PathVariable String userId,
        @RequestParam(required = false) String page,
        @RequestParam(required = false) String size
    ) {
        return ApiResponse.success(likeService.list(requester, userId, StrictInput.page(page), StrictInput.size(size)));
    }
}
