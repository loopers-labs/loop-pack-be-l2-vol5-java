package com.loopers.interfaces.api.payment;

import com.loopers.application.payment.PaymentApplyResult;
import com.loopers.application.payment.PaymentCallbackCommand;
import com.loopers.application.payment.PaymentCallbackFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
public class PaymentV1Controller implements PaymentV1ApiSpec {

    private final PaymentCallbackFacade callbackFacade;

    @PostMapping("/api/v1/payments/callback")
    @Override
    public ResponseEntity<ApiResponse<Object>> callback(@RequestBody(required = false) PaymentV1Dto.PaymentCallbackRequest request) {
        if (request == null) {
            throw new CoreException(ErrorType.INVALID_REQUEST);
        }
        PaymentApplyResult result = callbackFacade.handle(
            new PaymentCallbackCommand(request.providerOrderId(), request.transactionKey(), request.status()));
        HttpStatus status = result.outcome() == PaymentApplyResult.Outcome.FINAL ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(ApiResponse.success());
    }
}
