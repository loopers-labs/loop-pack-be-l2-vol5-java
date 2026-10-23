package com.loopers.interfaces.api.order;

import com.loopers.application.payment.RequestOrderPaymentFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.payment.PaymentDto;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ConfirmOrderController {
    private final RequestOrderPaymentFacade facade;

    @PostMapping("/api/v1/orders/{orderId}/confirm")
    public ResponseEntity<ApiResponse<PaymentDto.Result>> confirm(
            @RequestHeader(value = "X-USER-ID", required = false) Long userId,
            @PathVariable long orderId, @RequestBody PaymentDto.ConfirmRequest request) {
        var result = facade.request(userId, orderId, request.toCommand());
        return ResponseEntity.status(result.paymentStatus().isTerminal() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(ApiResponse.success(PaymentDto.Result.from(result, null)));
    }
}
