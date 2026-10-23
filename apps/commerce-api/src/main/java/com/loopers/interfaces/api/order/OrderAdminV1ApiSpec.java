package com.loopers.interfaces.api.order;

import com.loopers.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

import java.security.Principal;

@Tag(name = "Order Admin V1 API", description = "Loopers 관리자 주문 API 입니다.")
public interface OrderAdminV1ApiSpec {

    @Operation(
        summary = "전체 주문 목록 조회",
        description = "소유자와 무관하게 전체 주문 목록을 조회합니다. 결제 시도의 PG 식별자·확인 일정·관리자 처리 기록을 함께 반환합니다."
    )
    ApiResponse<OrderAdminV1Dto.OrderListResponse> getOrders();

    @Operation(
        summary = "주문 상세 조회",
        description = "소유자와 무관하게 주문 상세를 조회합니다. 결제 충돌 목록을 함께 반환합니다."
    )
    ApiResponse<OrderAdminV1Dto.OrderDetailResponse> getOrder(
        @Schema(name = "주문 ID", description = "조회할 주문의 ID")
        Long orderId
    );

    @Operation(
        summary = "결제 단건 확인",
        description = "이미 끝난 결제도 PG에 다시 조회합니다. 저장된 결과와 다르면 상태를 바꾸지 않고 충돌로 기록합니다. "
            + "결제가 아직 끝나지 않았으면 202, 끝났으면 200을 반환합니다. 결제를 시작하지 않은 주문이면 409입니다."
    )
    ResponseEntity<ApiResponse<OrderAdminV1Dto.OrderDetailResponse>> reconcilePayment(
        @Schema(name = "주문 ID", description = "확인할 주문의 ID")
        Long orderId
    );

    @Operation(
        summary = "결제 실패 처리",
        description = "PG에 거래가 없음을 확인한 결제를 실패로 처리하고 재고를 돌려줍니다. 처리자는 인증된 관리자 이름으로 기록하고, "
            + "이후 3일 동안 6시간마다 PG를 다시 조회해 늦게 나타난 거래를 충돌로 기록합니다. "
            + "거래 키를 모르는 미종결 결제(요청됨·결과 미확인)만 처리할 수 있고, 그 밖의 상태는 409입니다."
    )
    ApiResponse<OrderAdminV1Dto.OrderDetailResponse> failPayment(
        @Schema(name = "주문 ID", description = "실패 처리할 주문의 ID")
        Long orderId,
        @Parameter(hidden = true)
        Principal admin
    );
}
