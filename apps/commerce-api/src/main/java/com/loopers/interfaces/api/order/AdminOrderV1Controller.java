package com.loopers.interfaces.api.order;

import com.loopers.application.order.AdminOrderInfo;
import com.loopers.application.order.OrderService;
import com.loopers.domain.common.PageResult;
import com.loopers.domain.order.OrderStatus;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.commerce.AdminRequester;
import com.loopers.interfaces.api.commerce.InvalidRequestException;
import com.loopers.interfaces.api.commerce.StrictInput;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api-admin/v1/orders", produces = MediaType.APPLICATION_JSON_VALUE)
public class AdminOrderV1Controller {
    private final OrderService service;

    public AdminOrderV1Controller(OrderService service) {
        this.service = service;
    }

    @GetMapping("/{orderId}")
    public ApiResponse<AdminOrderInfo> detail(Authentication requester,
                                             @PathVariable String orderId) {
        return ApiResponse.success(service.adminDetail(AdminRequester.role(requester), StrictInput.id(orderId)));
    }

    @GetMapping
    public ApiResponse<PageResult<AdminOrderInfo>> list(
        Authentication requester,
        @RequestParam(required = false) String userId, @RequestParam(required = false) String status,
        @RequestParam(required = false) String page, @RequestParam(required = false) String size) {
        return ApiResponse.success(service.adminPage(AdminRequester.role(requester), userId, parseStatus(status),
            StrictInput.page(page), StrictInput.size(size)));
    }

    private OrderStatus parseStatus(String status) {
        if (status == null) {
            return null;
        }
        try {
            return OrderStatus.valueOf(status);
        } catch (IllegalArgumentException exception) {
            throw new InvalidRequestException();
        }
    }
}
