package com.loopers.interfaces.api.admin.order;

import com.loopers.application.order.OrderAdminFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageQuery;
import com.loopers.interfaces.api.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/orders")
public class OrderAdminV1Controller implements OrderAdminV1ApiSpec {

    private final OrderAdminFacade orderAdminFacade;

    @GetMapping
    @Override
    public ApiResponse<PageResponse<OrderAdminV1Dto.OrderSummaryResponse>> getOrders(
        @RequestParam(required = false) Long userId,
        @RequestParam(required = false) Integer page,
        @RequestParam(required = false) Integer size
    ) {
        var pageable = PageQuery.of(page, size).toPageable(Sort.by(Sort.Direction.DESC, "id"));
        return ApiResponse.success(
            PageResponse.from(orderAdminFacade.getOrders(userId, pageable), OrderAdminV1Dto.OrderSummaryResponse::from)
        );
    }

    @GetMapping("/{orderId}")
    @Override
    public ApiResponse<OrderAdminV1Dto.OrderResponse> getOrder(@PathVariable Long orderId) {
        return ApiResponse.success(OrderAdminV1Dto.OrderResponse.from(orderAdminFacade.getOrder(orderId)));
    }
}
