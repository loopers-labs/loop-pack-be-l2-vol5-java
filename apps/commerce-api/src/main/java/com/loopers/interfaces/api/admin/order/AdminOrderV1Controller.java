package com.loopers.interfaces.api.admin.order;

import com.loopers.application.order.AdminOrderFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageQuery;
import com.loopers.interfaces.api.PageResponse;
import com.loopers.interfaces.api.order.OrderStatusParam;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/orders")
public class AdminOrderV1Controller implements AdminOrderV1ApiSpec {

    private final AdminOrderFacade adminOrderFacade;

    @GetMapping
    @Override
    public ApiResponse<PageResponse<AdminOrderV1Dto.OrderSummaryResponse>> getOrders(
        @RequestParam(required = false) Long userId,
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.success(PageResponse.from(
            adminOrderFacade.getOrders(userId, OrderStatusParam.toStatus(status), PageQuery.of(page, size)),
            AdminOrderV1Dto.OrderSummaryResponse::from
        ));
    }

    @GetMapping("/{orderId}")
    @Override
    public ApiResponse<AdminOrderV1Dto.OrderResponse> getOrder(@PathVariable Long orderId) {
        return ApiResponse.success(AdminOrderV1Dto.OrderResponse.from(adminOrderFacade.getOrder(orderId)));
    }
}
