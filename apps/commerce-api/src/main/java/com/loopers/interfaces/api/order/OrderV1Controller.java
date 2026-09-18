package com.loopers.interfaces.api.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.application.order.OrderInfo;
import com.loopers.application.order.OrderService;
import com.loopers.domain.common.PageResult;
import com.loopers.domain.order.OrderQuantities;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.commerce.InvalidRequestException;
import com.loopers.interfaces.api.commerce.StrictInput;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping(value = "/api/v1/orders", produces = MediaType.APPLICATION_JSON_VALUE)
public class OrderV1Controller {
    private final OrderService service;

    public OrderV1Controller(OrderService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OrderInfo> create(@RequestHeader(value = "X-USER-ID", required = false) String requester,
                                         @RequestBody JsonNode body) {
        StrictInput.object(body, "items");
        JsonNode items = body.get("items");
        if (!items.isArray() || items.isEmpty()) {
            throw new InvalidRequestException();
        }
        List<OrderQuantities.Item> inputs = new ArrayList<>();
        for (JsonNode item : items) {
            StrictInput.object(item, "productId", "quantity");
            long productId = StrictInput.number(item, "productId");
            int quantity = StrictInput.integer(item, "quantity");
            if (productId <= 0 || quantity <= 0) {
                throw new InvalidRequestException();
            }
            inputs.add(new OrderQuantities.Item(productId, quantity));
        }
        return ApiResponse.success(service.create(requester, inputs));
    }

    @PostMapping("/{orderId}/confirm")
    public ApiResponse<OrderInfo> confirm(@RequestHeader(value = "X-USER-ID", required = false) String requester,
                                          @PathVariable String orderId) {
        return ApiResponse.success(service.confirm(requester, StrictInput.id(orderId)));
    }

    @GetMapping("/{orderId}")
    public ApiResponse<OrderInfo> detail(@RequestHeader(value = "X-USER-ID", required = false) String requester,
                                         @PathVariable String orderId) {
        return ApiResponse.success(service.getDetail(requester, StrictInput.id(orderId)));
    }

    @GetMapping
    public ApiResponse<PageResult<OrderInfo>> list(
        @RequestHeader(value = "X-USER-ID", required = false) String requester,
        @RequestParam(required = false) String page, @RequestParam(required = false) String size) {
        return ApiResponse.success(service.getPage(requester, StrictInput.page(page), StrictInput.size(size)));
    }
}
