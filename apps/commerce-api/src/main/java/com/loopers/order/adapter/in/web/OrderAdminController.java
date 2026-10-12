package com.loopers.order.adapter.in.web;

import com.loopers.order.adapter.in.web.dto.OrderAdminDto;
import com.loopers.order.adapter.in.web.spec.OrderAdminApiSpec;
import com.loopers.order.application.OrderAdminQueryService;
import com.loopers.support.web.ApiResponse;
import com.loopers.support.web.PageQuery;
import com.loopers.support.web.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/orders")
public class OrderAdminController implements OrderAdminApiSpec {

    private final OrderAdminQueryService orderAdminQueryService;

    @GetMapping
    @Override
    public ApiResponse<PageResponse<OrderAdminDto.OrderSummaryResponse>> getOrders(
        @RequestParam(required = false) Long userId,
        @RequestParam(required = false) Integer page,
        @RequestParam(required = false) Integer size
    ) {
        Pageable pageable = PageQuery.of(page, size).toPageable(Sort.by(Sort.Direction.DESC, "id"));
        return ApiResponse.success(
            PageResponse.from(orderAdminQueryService.getOrders(userId, pageable), OrderAdminDto.OrderSummaryResponse::from)
        );
    }

    @GetMapping("/{orderId}")
    @Override
    public ApiResponse<OrderAdminDto.OrderResponse> getOrder(@PathVariable Long orderId) {
        return ApiResponse.success(OrderAdminDto.OrderResponse.from(orderAdminQueryService.getOrder(orderId)));
    }
}
