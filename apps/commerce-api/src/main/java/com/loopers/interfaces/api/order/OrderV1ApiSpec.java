package com.loopers.interfaces.api.order;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import com.loopers.interfaces.api.auth.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Order V1 API", description = "주문 API 입니다. X-USER-ID 헤더로 요청자를 식별합니다.")
public interface OrderV1ApiSpec {

    @Operation(
        summary = "주문 생성",
        description = "DRAFT 로 저장하며 재고 · 포인트는 차감하지 않습니다. 같은 상품의 품목은 합산하므로 응답의 품목 수가 요청과 다를 수 있습니다."
    )
    ApiResponse<OrderV1Dto.OrderResponse> createOrder(@Parameter(hidden = true) LoginUser loginUser, OrderV1Dto.CreateRequest request);

    @Operation(
        summary = "주문 확정",
        description = "재고 · 포인트를 차감하고 CONFIRMED 로 바꿉니다. 상품 때문에 실패하면 data.productId 로 처음 실패한 상품을 알립니다."
    )
    ApiResponse<OrderV1Dto.OrderResponse> confirmOrder(
        @Parameter(hidden = true) LoginUser loginUser,
        @Schema(description = "주문 ID") Long orderId
    );

    @Operation(
        summary = "내 주문 목록",
        description = "status 를 생략하면 결제를 마친 CONFIRMED 만 보여줍니다. DRAFT 로 확정 전 주문을 볼 수 있습니다. 최근 주문 순입니다."
    )
    ApiResponse<PageResponse<OrderV1Dto.OrderSummaryResponse>> getMyOrders(
        @Parameter(hidden = true) LoginUser loginUser,
        @Schema(description = "주문 상태: CONFIRMED(기본), DRAFT") String status,
        @Schema(description = "페이지 (1부터)") int page,
        @Schema(description = "페이지 크기 (최대 100)") int size
    );

    @Operation(summary = "내 주문 상세", description = "없는 주문과 타인의 주문은 ORDER_NOT_FOUND 입니다. 품목은 주문 시점의 상품명 · 단가입니다.")
    ApiResponse<OrderV1Dto.OrderResponse> getMyOrder(
        @Parameter(hidden = true) LoginUser loginUser,
        @Schema(description = "주문 ID") Long orderId
    );
}
