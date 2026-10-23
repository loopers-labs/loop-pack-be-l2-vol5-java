package com.loopers.interfaces.api.payment;

import com.loopers.application.payment.ReceivePaymentCallbackFacade;
import com.loopers.interfaces.api.ApiResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PaymentCallbackController {
    private final ReceivePaymentCallbackFacade facade;

    @PostMapping("/api/v1/payments/callback")
    public ResponseEntity<ApiResponse<Void>> receive(@RequestBody PaymentDto.CallbackRequest request) {
        var result = facade.receive(request.toCommand());
        return ResponseEntity.status(result.payment().status().isTerminal() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(ApiResponse.success(null));
    }
}
