package com.loopers.interfaces.api.order;

import com.loopers.interfaces.api.ApiResponse;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Order V1 API", description = "Loopers 주문 API 입니다.")
public interface OrderV1ApiSpec {

    @Operation(
        summary = "주문 생성",
        description = "DRAFT 상태의 주문을 생성합니다. 재고·포인트는 차감하지 않습니다."
    )
    ApiResponse<OrderV1Dto.OrderResponse> createOrder(
        @Schema(name = "X-USER-ID", description = "요청자 ID")
        Long userId,
        OrderV1Dto.CreateRequest request
    );

    @Operation(
        summary = "주문 확정 (PG 결제 시작)",
        description = "DRAFT 주문의 재고를 확보하고 PG 결제를 시작합니다. 포인트는 차감하지 않습니다. "
            + "결제가 아직 끝나지 않았으면(처리중·결과 미확인) 202, 끝났으면 200을 반환합니다. "
            + "같은 paymentAttemptKey로 다시 요청하면 PG를 다시 부르지 않고 저장된 상태를 반환합니다."
    )
    ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> confirmOrder(
        @Schema(name = "X-USER-ID", description = "요청자 ID")
        Long userId,
        @Schema(name = "주문 ID", description = "확정할 주문의 ID")
        Long orderId,
        OrderV1Dto.ConfirmRequest request
    );

    @Operation(
        summary = "결제 결과 확인 (reconcile)",
        description = "이미 시작한 결제의 기존 거래를 PG에 조회해 반영합니다. 새 결제를 만들지 않습니다. "
            + "결제가 아직 끝나지 않았으면 202, 끝났으면 200을 반환합니다. 이미 끝난 결제는 PG를 부르지 않고 저장된 상태를 반환하고, "
            + "PG 조회에 실패해도 상태를 바꾸지 않고 202를 반환합니다. 결제를 시작하지 않은 주문이면 409를 반환합니다."
    )
    ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> reconcilePayment(
        @Schema(name = "X-USER-ID", description = "요청자 ID")
        Long userId,
        @Schema(name = "주문 ID", description = "결제를 확인할 주문의 ID")
        Long orderId
    );

    @Operation(
        summary = "내 주문 목록 조회",
        description = "요청자의 주문 목록을 조회합니다."
    )
    ApiResponse<OrderV1Dto.OrderListResponse> getMyOrders(
        @Schema(name = "X-USER-ID", description = "요청자 ID")
        Long userId
    );

    @Operation(
        summary = "내 주문 상세 조회",
        description = "요청자의 주문 상세를 조회합니다. 요청자가 소유자가 아니면 404를 반환합니다(존재 비노출)."
    )
    ApiResponse<OrderV1Dto.OrderResponse> getMyOrder(
        @Schema(name = "X-USER-ID", description = "요청자 ID")
        Long userId,
        @Schema(name = "주문 ID", description = "조회할 주문의 ID")
        Long orderId
    );
}
