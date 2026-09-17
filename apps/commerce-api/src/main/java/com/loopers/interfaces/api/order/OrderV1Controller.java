package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.support.LoginUser;
import com.loopers.interfaces.api.support.PageQuery;
import com.loopers.interfaces.api.support.PageResponse;
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
public class OrderV1Controller implements OrderV1ApiSpec {

    private final OrderFacade orderFacade;

    @PostMapping
    @Override
    public ApiResponse<OrderV1Dto.OrderResponse> createOrder(LoginUser loginUser, @RequestBody OrderV1Dto.CreateRequest request) {
        return ApiResponse.success(OrderV1Dto.OrderResponse.from(orderFacade.create(loginUser.id(), request.toLines())));
    }

    @PostMapping("/{orderId}/confirm")
    @Override
    public ApiResponse<OrderV1Dto.OrderResponse> confirmOrder(LoginUser loginUser, @PathVariable Long orderId) {
        return ApiResponse.success(OrderV1Dto.OrderResponse.from(orderFacade.confirm(loginUser.id(), orderId)));
    }

    @GetMapping
    @Override
    public ApiResponse<PageResponse<OrderV1Dto.OrderSummaryResponse>> getMyOrders(
        LoginUser loginUser,
        @RequestParam(required = false) Integer page,
        @RequestParam(required = false) Integer size
    ) {
        Pageable pageable = PageQuery.of(page, size).toPageable(Sort.by(Sort.Direction.DESC, "id"));
        return ApiResponse.success(
            PageResponse.from(orderFacade.getMyOrders(loginUser.id(), pageable), OrderV1Dto.OrderSummaryResponse::from)
        );
    }

    @GetMapping("/{orderId}")
    @Override
    public ApiResponse<OrderV1Dto.OrderResponse> getMyOrder(LoginUser loginUser, @PathVariable Long orderId) {
        return ApiResponse.success(OrderV1Dto.OrderResponse.from(orderFacade.getMyOrder(loginUser.id(), orderId)));
    }
}
