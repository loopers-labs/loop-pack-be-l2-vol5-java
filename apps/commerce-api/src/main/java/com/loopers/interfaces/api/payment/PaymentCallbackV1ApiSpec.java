package com.loopers.interfaces.api.payment;

import com.loopers.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Payment Callback V1 API", description = "테스트 PG가 결제 결과를 알리는 API 입니다.")
public interface PaymentCallbackV1ApiSpec {

    @Operation(
        summary = "결제 결과 알림 수신",
        description = "PG의 결과 알림을 원문 그대로 수신함에 저장하고 200을 반환합니다. 200은 \"받아서 저장했다\"는 뜻이며 처리 결과와 상관없습니다. "
            + "본문의 상태로 주문을 확정하지 않고, 회복 작업이 PG에 결과를 다시 조회해 반영합니다."
    )
    ApiResponse<Object> receive(String rawPayload);
}
