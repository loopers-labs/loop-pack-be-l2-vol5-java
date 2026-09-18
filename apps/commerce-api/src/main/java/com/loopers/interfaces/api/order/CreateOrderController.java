package com.loopers.interfaces.api.order;

import com.loopers.application.order.CreateOrderFacade;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class CreateOrderController {
    private final CreateOrderFacade facade;
    @PostMapping("/api/v1/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OrderDto.Customer> create(@RequestHeader(value = "X-USER-ID", required = false) Long userId,
                                                @RequestBody OrderDto.Create request) {
        return ApiResponse.success(OrderDto.Customer.from(facade.create(userId,request.toCommand())));
    }
}
