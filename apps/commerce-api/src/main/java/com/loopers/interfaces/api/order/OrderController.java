package com.loopers.interfaces.api.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.application.order.OrderApplicationService;
import com.loopers.application.order.OrderResult;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.CustomerIdentity;
import com.loopers.interfaces.api.RequestValues;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
public class OrderController {
    private final OrderApplicationService service;
    private final CustomerIdentity identity;
    public OrderController(OrderApplicationService service, CustomerIdentity identity) { this.service = service; this.identity = identity; }

    @PostMapping("/api/v1/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OrderResult> create(@RequestHeader(value = "X-USER-ID", required = false) String user,
        @RequestBody CreateRequest request) {
        long userId = identity.require(user);
        if (request.items() == null || request.items().isEmpty() || request.items().size() > 100
            || request.items().stream().anyMatch(java.util.Objects::isNull)) { throw new CoreException(ErrorType.BAD_REQUEST); }
        return ApiResponse.success(service.create(userId, request.items().stream()
            .map(i -> new OrderApplicationService.ItemRequest(RequestValues.integer(i.productId()), RequestValues.stock(i.quantity()))).toList()));
    }
    @PostMapping("/api/v1/orders/{id}/confirm")
    public ApiResponse<OrderResult> confirm(@RequestHeader(value = "X-USER-ID", required = false) String user, @PathVariable long id) {
        return ApiResponse.success(service.confirm(identity.require(user), id));
    }
    @GetMapping("/api/v1/orders/{id}")
    public ApiResponse<OrderResult> get(@RequestHeader(value = "X-USER-ID", required = false) String user, @PathVariable long id) {
        return ApiResponse.success(service.getMyOrder(identity.require(user), id));
    }
    @GetMapping("/api/v1/orders")
    public ApiResponse<List<OrderResult>> list(@RequestHeader(value = "X-USER-ID", required = false) String user,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        RequestValues.page(page, size);
        return ApiResponse.success(service.listMyOrders(identity.require(user), page, size));
    }
    public record CreateRequest(List<ItemRequest> items) { }
    public record ItemRequest(JsonNode productId, JsonNode quantity) { }
}
