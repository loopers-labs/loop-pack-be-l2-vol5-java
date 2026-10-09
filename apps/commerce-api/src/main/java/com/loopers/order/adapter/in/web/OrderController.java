package com.loopers.order.adapter.in.web;

import com.loopers.order.adapter.in.web.dto.OrderDto;
import com.loopers.order.adapter.in.web.spec.OrderApiSpec;
import com.loopers.order.application.OrderQueryService;
import com.loopers.order.application.port.in.OrderCommandUseCase;
import com.loopers.support.web.ApiResponse;
import com.loopers.support.web.PageQuery;
import com.loopers.support.web.PageResponse;
import com.loopers.user.adapter.in.web.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController implements OrderApiSpec {

    private final OrderCommandUseCase orderCommandUseCase;
    private final OrderQueryService orderQueryService;

    @PostMapping
    @Override
    public ApiResponse<OrderDto.OrderResponse> createOrder(LoginUser loginUser, @RequestBody OrderDto.CreateRequest request) {
        return ApiResponse.success(OrderDto.OrderResponse.from(orderCommandUseCase.create(loginUser.id(), request.toLines())));
    }

    @PostMapping("/{orderId}/confirm")
    @Override
    public ApiResponse<OrderDto.OrderResponse> confirmOrder(LoginUser loginUser, @PathVariable Long orderId) {
        return ApiResponse.success(OrderDto.OrderResponse.from(orderCommandUseCase.confirm(loginUser.id(), orderId)));
    }

    @GetMapping
    @Override
    public ApiResponse<PageResponse<OrderDto.OrderSummaryResponse>> getMyOrders(
        LoginUser loginUser,
        @RequestParam(required = false) Integer page,
        @RequestParam(required = false) Integer size
    ) {
        Pageable pageable = PageQuery.of(page, size).toPageable(Sort.by(Sort.Direction.DESC, "id"));
        return ApiResponse.success(
            PageResponse.from(orderQueryService.getMyOrders(loginUser.id(), pageable), OrderDto.OrderSummaryResponse::from)
        );
    }

    @GetMapping("/{orderId}")
    @Override
    public ApiResponse<OrderDto.OrderResponse> getMyOrder(LoginUser loginUser, @PathVariable Long orderId) {
        return ApiResponse.success(OrderDto.OrderResponse.from(orderQueryService.getMyOrder(loginUser.id(), orderId)));
    }
}
