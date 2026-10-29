package com.loopers.interfaces.api.payment;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.application.order.OrderApplicationService;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.RequestValues;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentCallbackController {
    private final OrderApplicationService orderApplicationService;

    public PaymentCallbackController(OrderApplicationService orderApplicationService) {
        this.orderApplicationService = orderApplicationService;
    }

    public record CallbackRequest(@NotBlank String transactionKey,
        @JsonAlias("orderId") @NotBlank String providerOrderId, JsonNode amount, @NotBlank String status) {
    }

    @PostMapping("/api/v1/payments/callback")
    public ApiResponse<Void> callback(@Valid @RequestBody CallbackRequest request) {
        if (request.amount() != null && RequestValues.integer(request.amount()) < 0) {
            throw new CoreException(ErrorType.BAD_REQUEST);
        }
        orderApplicationService.receivePaymentCallback(request.providerOrderId(), request.transactionKey());
        return ApiResponse.success(null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Object> invalidCallback() {
        return ApiResponse.fail(ErrorType.BAD_REQUEST.getCode(), null);
    }
}
