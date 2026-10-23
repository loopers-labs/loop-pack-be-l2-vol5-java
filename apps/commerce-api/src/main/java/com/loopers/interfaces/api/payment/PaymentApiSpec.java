package com.loopers.interfaces.api.payment;

import com.loopers.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Payment V1 API", description = "PG 결제 결과 알림을 받는 API 입니다.")
public interface PaymentApiSpec {

    @Operation(
        summary = "PG 결제 결과 알림",
        description = "PG 가 결제 결과를 알리는 요청입니다. 요청자 식별 헤더(X-USER-ID)를 쓰지 않습니다. 알림의 status 로 주문을 "
            + "확정하거나 실패시키지 않습니다. 알림은 확인을 요청하는 신호일 뿐이고, providerOrderId 로 찾은 결제 시도에 대해 "
            + "transactionKey 로 PG 를 조회해서 확인한 사실(계정·주문·거래·금액 일치)만 반영합니다. 조회 결과가 다르거나 "
            + "시도와 맞지 않으면 상태를 바꾸지 않고, 이미 끝난 시도에 같은 알림이 다시 와도 아무 효과가 없습니다. 알 수 없는 "
            + "providerOrderId 는 404 이며 아무것도 바꾸지 않습니다. 응답은 수신 확인이며 주문과 시도의 현재 상태를 담습니다."
    )
    ApiResponse<PaymentDto.CallbackResponse> callback(PaymentDto.CallbackRequest request);
}
