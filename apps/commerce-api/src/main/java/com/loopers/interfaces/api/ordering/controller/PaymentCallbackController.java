package com.loopers.interfaces.api.ordering.controller;

import com.loopers.application.ordering.result.PaymentCallbackResult;
import com.loopers.application.ordering.usecase.HandlePaymentCallbackUseCase;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.ordering.dto.PaymentCallbackRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PaymentCallbackController {
    private final HandlePaymentCallbackUseCase callback;

    // 고객/관리자 헤더 대신 등록 시도와 고정 PG adapter의 권위 조회로 확인한다.
    @PostMapping("/api/v1/payments/callback")
    public ResponseEntity<ApiResponse<PaymentCallbackResult>> handle(@RequestBody PaymentCallbackRequest request) {
        PaymentCallbackResult result = callback.execute(request.toCommand());
        return ResponseEntity.status(result.completed() ? HttpStatus.OK : HttpStatus.ACCEPTED).body(ApiResponse.success(result));
    }
}
