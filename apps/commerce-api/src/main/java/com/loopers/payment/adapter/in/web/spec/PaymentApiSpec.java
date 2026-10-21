package com.loopers.payment.adapter.in.web.spec;

import com.loopers.payment.adapter.in.web.dto.PaymentDto;
import com.loopers.support.web.ApiResponse;
import com.loopers.user.adapter.in.web.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Payment V1 API", description = "주문 결제(PG) API — 확정·결제 상태·즉시 확인·PG callback (W5 plan.md 8)")
public interface PaymentApiSpec {

    @Operation(
        summary = "주문 확정 (PG 결제 요청)",
        description = "본인의 DRAFT 주문을 PG로 결제합니다. 헤더 Idempotency-Key(필수)가 결제 시도 키입니다. "
            + "재고를 선점하고 결제를 접수한 뒤 PG 발송은 비동기로 진행합니다. "
            + "결과가 아직이면 202, 이미 정해졌으면(즉시 실패 포함) 200. 같은 키 재요청은 같은 시도 상태를 돌려줍니다. "
            + "키·카드 누락 400, 남의·없는 주문 404, 다른 주문에 쓴 키·이미 다른 키로 시도한 주문·DRAFT 아님·상품 변경·재고 부족 409"
    )
    ResponseEntity<ApiResponse<PaymentDto.PaymentResponse>> confirmOrder(
        @Parameter(hidden = true) LoginUser loginUser,
        Long orderId,
        @Parameter(description = "결제 시도 키 (UUID 권장)", required = true) String idempotencyKey,
        PaymentDto.ConfirmRequest request
    );

    @Operation(summary = "주문 결제 상태", description = "본인 주문의 결제 시도 상태. PG를 부르지 않습니다. 없거나 남의 주문·결제 시도 없음 404")
    ApiResponse<PaymentDto.PaymentResponse> getPayment(@Parameter(hidden = true) LoginUser loginUser, Long orderId);

    @Operation(
        summary = "결제 결과 즉시 확인",
        description = "본인 주문의 결제를 PG에 조회해 결과를 반영합니다. 결제를 새로 만들지 않습니다. 결과가 아직이면 202, 정해졌으면 200"
    )
    ResponseEntity<ApiResponse<PaymentDto.PaymentResponse>> reconcilePayment(@Parameter(hidden = true) LoginUser loginUser, Long orderId);

    @Operation(
        summary = "PG callback",
        description = "PG가 거래 상태 변경을 알립니다 (X-USER-ID 없음). 본문은 계기일 뿐이고 PG 권위 조회로 반영합니다. 모르는 orderId 404"
    )
    ApiResponse<Object> callback(PaymentDto.CallbackRequest request);
}
