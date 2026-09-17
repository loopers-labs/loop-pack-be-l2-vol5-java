package com.loopers.interfaces.api.order;

import com.loopers.domain.common.ListSort;
import com.loopers.domain.common.PageCommand;
import com.loopers.domain.common.PageResult;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderService;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자는 주문을 조회만 하며 상태를 변경하지 않는다.
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/orders")
public class OrderAdminV1Controller implements OrderAdminV1ApiSpec {

    private final OrderService orderService;

    @GetMapping
    @Override
    public ApiResponse<PageResponse<OrderAdminV1Dto.AdminOrderResponse>> getOrders(
        @RequestParam(value = "page", required = false) Integer page,
        @RequestParam(value = "size", required = false) Integer size,
        @RequestParam(value = "sort", required = false) String sort
    ) {
        PageResult<OrderModel> result = orderService.getAllOrders(PageCommand.of(page, size), ListSort.from(sort));
        return ApiResponse.success(PageResponse.of(result, OrderAdminV1Dto.AdminOrderResponse::from));
    }

    @GetMapping("/{orderId}")
    @Override
    public ApiResponse<OrderAdminV1Dto.AdminOrderResponse> getOrder(@PathVariable(value = "orderId") Long orderId) {
        return ApiResponse.success(OrderAdminV1Dto.AdminOrderResponse.from(orderService.getAnyOrder(orderId)));
    }
}
