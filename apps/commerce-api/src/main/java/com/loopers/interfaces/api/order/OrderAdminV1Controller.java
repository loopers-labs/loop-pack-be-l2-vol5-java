package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import com.loopers.interfaces.api.auth.RequesterId;
import com.loopers.support.paging.PageQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/orders")
public class OrderAdminV1Controller implements OrderAdminV1ApiSpec {

    private final OrderFacade orderFacade;

    @GetMapping
    @Override
    public ApiResponse<PageResponse<OrderAdminV1Dto.AdminOrderGroupResponse>> listOrders(
        @RequesterId Long requesterId,
        @RequestParam(value = "page", required = false) Integer page,
        @RequestParam(value = "size", required = false) Integer size
    ) {
        var result = orderFacade.listOrdersForAdmin(requesterId, PageQuery.of(page, size));
        return ApiResponse.success(PageResponse.from(result, OrderAdminV1Dto.AdminOrderGroupResponse::from));
    }

    @GetMapping("/{orderId}")
    @Override
    public ApiResponse<OrderAdminV1Dto.AdminOrderResponse> getOrder(
        @RequesterId Long requesterId,
        @PathVariable("orderId") Long orderId
    ) {
        return ApiResponse.success(OrderAdminV1Dto.AdminOrderResponse.from(orderFacade.getOrderForAdmin(requesterId, orderId)));
    }
}
