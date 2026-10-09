package com.loopers.interfaces.api.shopping.controller;

import com.loopers.application.shopping.command.LikeCommand;
import com.loopers.application.shopping.usecase.CancelLikeUseCase;
import com.loopers.application.shopping.usecase.RegisterLikeUseCase;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.support.RequestInputValidator;
import com.loopers.interfaces.api.support.XUserId;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products/{productId}/likes")
@RequiredArgsConstructor
// 좋아요 등록/취소 API 컨트롤러
public class LikeController {
    private final RegisterLikeUseCase registerLikeUseCase;
    private final CancelLikeUseCase cancelLikeUseCase;

    // 좋아요 등록 요청 처리
    @PostMapping
    public ApiResponse<Object> register(@XUserId long userId, @PathVariable long productId) {
        RequestInputValidator.requirePositiveId(productId, "상품 ID");
        registerLikeUseCase.execute(new LikeCommand.Register(userId, productId));
        return ApiResponse.success();
    }

    // 좋아요 취소 요청 처리
    @DeleteMapping
    public ApiResponse<Object> cancel(@XUserId long userId, @PathVariable long productId) {
        RequestInputValidator.requirePositiveId(productId, "상품 ID");
        cancelLikeUseCase.execute(new LikeCommand.Cancel(userId, productId));
        return ApiResponse.success();
    }
}
