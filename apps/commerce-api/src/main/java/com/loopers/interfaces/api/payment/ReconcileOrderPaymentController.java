package com.loopers.interfaces.api.payment;

import com.loopers.application.payment.ReconcileOrderPaymentFacade;
import com.loopers.application.payment.RequestOrderPaymentFacade;
import com.loopers.interfaces.api.ApiResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ReconcileOrderPaymentController {
    private final ReconcileOrderPaymentFacade facade;

    @PostMapping("/api/v1/orders/{orderId}/payment/reconcile")
    public ResponseEntity<ApiResponse<PaymentDto.Result>> reconcile(
            @RequestHeader(value = "X-USER-ID", required = false) Long userId, @PathVariable long orderId) {
        var result = facade.reconcile(userId, orderId);
        return ResponseEntity.status(result.payment().status().isTerminal() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(ApiResponse.success(PaymentDto.Result.from(
                        RequestOrderPaymentFacade.Result.from(result.payment()), result.lookupAvailable())));
    }
}
