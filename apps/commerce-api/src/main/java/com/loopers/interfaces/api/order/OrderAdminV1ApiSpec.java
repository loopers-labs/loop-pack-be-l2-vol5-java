package com.loopers.interfaces.api.order;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Order Admin V1 API", description = "주문 관리자 API")
public interface OrderAdminV1ApiSpec {

    /** EP-25 GET /api-admin/v1/orders — FR-ADMIN-ORDER-01 */
    @Operation(summary = "주문 목록 (구매자별)", description = "전체 주문을 구매자 단위로 묶어 반환한다. 페이지 단위는 구매자 묶음.")
    ApiResponse<PageResponse<OrderAdminV1Dto.AdminOrderGroupResponse>> listOrders(Long requesterId, Integer page, Integer size);

    /** EP-26 GET /api-admin/v1/orders/{orderId} — FR-ADMIN-ORDER-02 */
    @Operation(summary = "주문 상세", description = "구매자·품목·금액·상태·결제 결과를 반환한다.")
    ApiResponse<OrderAdminV1Dto.AdminOrderResponse> getOrder(Long requesterId, Long orderId);
}
