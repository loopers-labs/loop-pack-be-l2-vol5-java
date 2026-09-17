package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderApplicationService;
import com.loopers.application.order.OrderResult;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.RequestValues;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
public class AdminOrderController {
    private final OrderApplicationService service;
    public AdminOrderController(OrderApplicationService service) { this.service = service; }
    @GetMapping("/api-admin/v1/orders/{id}")
    public ApiResponse<OrderResult> get(@PathVariable long id) { return ApiResponse.success(service.getAdminOrder(id)); }
    @GetMapping("/api-admin/v1/orders")
    public ApiResponse<List<OrderResult>> list(@RequestParam(required = false) Long userId,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        RequestValues.page(page, size);
        if (userId != null && userId <= 0) { throw new CoreException(ErrorType.BAD_REQUEST); }
        return ApiResponse.success(service.listAdminOrders(userId, page, size));
    }
}
