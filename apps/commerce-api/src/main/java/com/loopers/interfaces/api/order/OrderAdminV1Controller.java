package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderFacade;
import com.loopers.domain.common.PageNumber;
import com.loopers.domain.common.PageSize;
import com.loopers.domain.order.OrderStatus;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api-admin/v1/orders")
@RequiredArgsConstructor
public class OrderAdminV1Controller {

    private final OrderFacade orderFacade;

    @GetMapping
    public ApiResponse<OrderAdminV1Dto.AdminOrderPageResponse> findPage(
        @RequestParam(required = false) Long userId,
        @RequestParam(required = false) OrderStatus status,
        @RequestParam(defaultValue = "0") PageNumber page,
        @RequestParam(defaultValue = "20") PageSize size
    ) {
        return ApiResponse.success(OrderAdminV1Dto.AdminOrderPageResponse.of(
            orderFacade.findPageForAdmin(userId, status, page, size), page.value(), size.value()));
    }

    @GetMapping("/{orderId}")
    public ApiResponse<OrderAdminV1Dto.AdminOrderResponse> get(@PathVariable Long orderId) {
        return ApiResponse.success(
            OrderAdminV1Dto.AdminOrderResponse.from(orderFacade.getForAdmin(orderId)));
    }
}
