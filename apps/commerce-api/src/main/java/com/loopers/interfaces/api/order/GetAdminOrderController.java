package com.loopers.interfaces.api.order;

import com.loopers.application.order.GetAdminOrderFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class GetAdminOrderController {
    private final GetAdminOrderFacade facade;
    @GetMapping("/api-admin/v1/orders/{orderId}")
    public ApiResponse<OrderDto.Admin> get(@PathVariable long orderId) {
        return ApiResponse.success(OrderDto.Admin.from(facade.get(orderId)));
    }
    @GetMapping("/api-admin/v1/orders")
    public ApiResponse<PageResponse<OrderDto.Admin>> list(@RequestParam(required = false) Long userId,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(PageResponse.from(facade.list(userId,page,size).map(OrderDto.Admin::from)));
    }
}
