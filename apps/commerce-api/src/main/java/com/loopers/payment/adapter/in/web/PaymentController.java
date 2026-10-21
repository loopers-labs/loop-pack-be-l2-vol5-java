package com.loopers.payment.adapter.in.web;

import com.loopers.payment.adapter.in.web.dto.PaymentDto;
import com.loopers.payment.adapter.in.web.spec.PaymentApiSpec;
import com.loopers.support.web.ApiResponse;
import com.loopers.user.adapter.in.web.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
public class PaymentController implements PaymentApiSpec {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    @PostMapping("/api/v1/orders/{orderId}/confirm")
    @Override
    public ResponseEntity<ApiResponse<PaymentDto.PaymentResponse>> confirmOrder(
        LoginUser loginUser,
        @PathVariable Long orderId,
        @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
        @RequestBody PaymentDto.ConfirmRequest request
    ) {
        return ResponseEntity.internalServerError().build();
    }

    @GetMapping("/api/v1/orders/{orderId}/payment")
    @Override
    public ApiResponse<PaymentDto.PaymentResponse> getPayment(LoginUser loginUser, @PathVariable Long orderId) {
        return null;
    }

    @PostMapping("/api/v1/orders/{orderId}/payment/reconcile")
    @Override
    public ResponseEntity<ApiResponse<PaymentDto.PaymentResponse>> reconcilePayment(LoginUser loginUser, @PathVariable Long orderId) {
        return ResponseEntity.internalServerError().build();
    }

    @PostMapping("/api/v1/payments/callback")
    @Override
    public ApiResponse<Object> callback(@RequestBody PaymentDto.CallbackRequest request) {
        return null;
    }
}
