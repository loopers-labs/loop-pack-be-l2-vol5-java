package com.loopers.interfaces.api.payment;

import com.loopers.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Payment V1 API", description = "PG 콜백 수신")
public interface PaymentV1ApiSpec {
    ResponseEntity<ApiResponse<Object>> callback(PaymentV1Dto.PaymentCallbackRequest request);
}
