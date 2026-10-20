package com.loopers.interfaces.api.order;

import com.loopers.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Payment Callback V1 API", description = "PG 결제 결과 callback")
public interface PaymentCallbackV1ApiSpec {

    /** POST /api/v1/payments/callback — FR-ORDER-02 결과 반영 */
    @Operation(summary = "PG 결제 결과 callback", description = "PG 가 결제 결과를 알린다. 이미 결론난 주문·중복 callback 은 무시하고 200. 없는 주문이면 ORDER_NOT_FOUND.")
    ApiResponse<Object> callback(PaymentCallbackV1Dto.CallbackRequest request);
}
