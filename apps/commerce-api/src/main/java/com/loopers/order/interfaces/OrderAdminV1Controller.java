package com.loopers.order.interfaces;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.ListResponse;
import com.loopers.interfaces.api.PageQuery;
import com.loopers.order.application.OrderUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/orders")
public class OrderAdminV1Controller {

    private final OrderUseCase orderUseCase;

    @GetMapping
    public ApiResponse<ListResponse<OrderV1Dto.AdminOrderSummaryResponse>> getOrders(
        @RequestParam(required = false) Long buyerId,
        @RequestParam(defaultValue = PageQuery.DEFAULT_PAGE) int page,
        @RequestParam(defaultValue = PageQuery.DEFAULT_SIZE) int size
    ) {
        PageQuery query = new PageQuery(page, size);
        return ApiResponse.success(ListResponse.from(
            orderUseCase.findPageForAdmin(buyerId, query.page(), query.size()),
            OrderV1Dto.AdminOrderSummaryResponse::from
        ));
    }

    @GetMapping("/{orderId}")
    public ApiResponse<OrderV1Dto.AdminOrderDetailResponse> getOrder(@PathVariable Long orderId) {
        return ApiResponse.success(OrderV1Dto.AdminOrderDetailResponse.from(orderUseCase.findForAdmin(orderId)));
    }
}
