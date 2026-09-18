package com.loopers.interfaces.api.admin.order;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Admin Order V1 API", description = "관리자용 주문 조회 API 입니다.")
public interface AdminOrderV1ApiSpec {

    @Operation(
        summary = "주문 목록",
        description = "구매자들의 주문을 최근 순으로 조회합니다. status 를 생략하면 전체입니다. 없는 userId 는 USER_NOT_FOUND 입니다."
    )
    ApiResponse<PageResponse<AdminOrderV1Dto.OrderSummaryResponse>> getOrders(
        @Schema(description = "구매자 ID (선택)") Long userId,
        @Schema(description = "주문 상태 (선택): DRAFT, CONFIRMED") String status,
        @Schema(description = "페이지 (1부터)") int page,
        @Schema(description = "페이지 크기 (최대 100)") int size
    );

    @Operation(summary = "주문 상세", description = "삭제된 상품이 포함된 주문도 주문 시점의 상품명 · 단가로 보입니다.")
    ApiResponse<AdminOrderV1Dto.OrderResponse> getOrder(@Schema(description = "주문 ID") Long orderId);
}
