package com.loopers.interfaces.api.payment;

import com.loopers.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Payment V1 API", description = "테스트 PG 가 부르는 결제 API 입니다.")
public interface PaymentV1ApiSpec {

    @Operation(
        summary = "결제 결과 콜백",
        description = "본문의 orderId 로 결제 시도를 찾아 테스트 PG 에서 다시 조회한 결과만 반영합니다. 본문의 status 는 반영하지 않습니다. "
            + "처리하면 결제가 끝나지 않았어도 200 입니다. orderId 가 없으면 INVALID_PAYMENT_REQUEST, 모르는 주문이면 PAYMENT_NOT_FOUND 입니다."
    )
    ApiResponse<Object> handleCallback(PaymentV1Dto.CallbackRequest request);
}
