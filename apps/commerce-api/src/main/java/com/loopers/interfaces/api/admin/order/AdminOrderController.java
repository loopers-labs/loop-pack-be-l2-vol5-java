package com.loopers.interfaces.api.admin.order;

import com.loopers.application.order.AdminOrderFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/orders")
public class AdminOrderController implements AdminOrderApiSpec {

    private final AdminOrderFacade adminOrderFacade;

    @GetMapping
    @Override
    public ApiResponse<PageResponse<AdminOrderDto.OrderResponse>> getOrders(
        @RequestParam(value = "userId", required = false) Long userId,
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        return ApiResponse.success(PageResponse.from(adminOrderFacade.getOrders(userId, page, size), AdminOrderDto.OrderResponse::from));
    }

    @GetMapping("/{orderId}")
    @Override
    public ApiResponse<AdminOrderDto.OrderResponse> getOrder(@PathVariable(value = "orderId") Long orderId) {
        return ApiResponse.success(AdminOrderDto.OrderResponse.from(adminOrderFacade.getOrder(orderId)));
    }
}
