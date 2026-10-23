package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderAdminFacade;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/orders")
public class OrderAdminV1Controller implements OrderAdminV1ApiSpec {

    private final OrderAdminFacade orderAdminFacade;

    @GetMapping
    @Override
    public ApiResponse<OrderAdminV1Dto.OrderListResponse> getOrders() {
        return ApiResponse.success(OrderAdminV1Dto.OrderListResponse.from(orderAdminFacade.getOrders()));
    }

    @GetMapping("/{orderId}")
    @Override
    public ApiResponse<OrderAdminV1Dto.OrderDetailResponse> getOrder(
        @PathVariable(value = "orderId") Long orderId
    ) {
        return ApiResponse.success(OrderAdminV1Dto.OrderDetailResponse.from(orderAdminFacade.getOrder(orderId)));
    }

    @PostMapping("/{orderId}/payment/reconcile")
    @Override
    public ResponseEntity<ApiResponse<OrderAdminV1Dto.OrderDetailResponse>> reconcilePayment(
        @PathVariable(value = "orderId") Long orderId
    ) {
        OrderAdminV1Dto.OrderDetailResponse body = OrderAdminV1Dto.OrderDetailResponse.from(orderAdminFacade.reconcilePayment(orderId));
        HttpStatus status = body.paymentInProgress() ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.success(body));
    }

    // 처리자는 요청 본문이 아니라 인증된 관리자 이름으로 적는다(docs/week5/design.md D-046).
    @PostMapping("/{orderId}/payment/fail")
    @Override
    public ApiResponse<OrderAdminV1Dto.OrderDetailResponse> failPayment(
        @PathVariable(value = "orderId") Long orderId,
        Principal admin
    ) {
        return ApiResponse.success(OrderAdminV1Dto.OrderDetailResponse.from(orderAdminFacade.failPayment(orderId, admin.getName())));
    }
}
