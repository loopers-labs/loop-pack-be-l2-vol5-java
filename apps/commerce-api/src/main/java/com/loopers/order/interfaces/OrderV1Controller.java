package com.loopers.order.interfaces;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.ListResponse;
import com.loopers.interfaces.api.PageQuery;
import com.loopers.interfaces.api.Requester;
import com.loopers.order.application.OrderUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/orders")
public class OrderV1Controller {

    private final OrderUseCase orderUseCase;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OrderV1Dto.OrderDetailResponse> createOrder(
        Requester requester,
        @RequestBody OrderV1Dto.CreateOrderRequest request
    ) {
        return ApiResponse.success(OrderV1Dto.OrderDetailResponse.from(
            orderUseCase.create(requester.userId(), request.toCommands())
        ));
    }

    @PostMapping("/{orderId}/confirm")
    public ApiResponse<OrderV1Dto.OrderDetailResponse> confirmOrder(
        Requester requester,
        @PathVariable Long orderId
    ) {
        return ApiResponse.success(OrderV1Dto.OrderDetailResponse.from(
            orderUseCase.confirm(requester.userId(), orderId)
        ));
    }

    @GetMapping
    public ApiResponse<ListResponse<OrderV1Dto.OrderSummaryResponse>> getMyOrders(
        Requester requester,
        @RequestParam(defaultValue = PageQuery.DEFAULT_PAGE) int page,
        @RequestParam(defaultValue = PageQuery.DEFAULT_SIZE) int size
    ) {
        PageQuery query = new PageQuery(page, size);
        return ApiResponse.success(ListResponse.from(
            orderUseCase.findMinePage(requester.userId(), query.page(), query.size()),
            OrderV1Dto.OrderSummaryResponse::from
        ));
    }

    @GetMapping("/{orderId}")
    public ApiResponse<OrderV1Dto.OrderDetailResponse> getMyOrder(
        Requester requester,
        @PathVariable Long orderId
    ) {
        return ApiResponse.success(OrderV1Dto.OrderDetailResponse.from(
            orderUseCase.findMine(requester.userId(), orderId)
        ));
    }
}
