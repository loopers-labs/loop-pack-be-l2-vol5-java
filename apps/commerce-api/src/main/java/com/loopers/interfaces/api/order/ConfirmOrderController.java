package com.loopers.interfaces.api.order;

import com.loopers.application.order.ConfirmOrderFacade;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ConfirmOrderController {
    private final ConfirmOrderFacade facade;
    @PostMapping("/api/v1/orders/{orderId}/confirm")
    public ApiResponse<OrderDto.Customer> confirm(@RequestHeader(value = "X-USER-ID", required = false) Long userId,
                                                 @PathVariable long orderId) {
        return ApiResponse.success(OrderDto.Customer.from(facade.confirm(userId,orderId)));
    }
}
