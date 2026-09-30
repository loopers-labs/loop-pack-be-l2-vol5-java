package com.loopers.interfaces.api.ordering.controller;

import com.loopers.application.ordering.command.ConfirmOrderCommand;
import com.loopers.application.ordering.result.ConfirmOrderResult;
import com.loopers.application.ordering.usecase.ConfirmOrderUseCase;
import com.loopers.application.ordering.usecase.CreateOrderUseCase;
import com.loopers.application.ordering.query.OrderView;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.ordering.dto.OrderApiDto;
import com.loopers.interfaces.api.support.RequestInputValidator;
import com.loopers.interfaces.api.support.XUserId;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
// 주문 생성/확정 컨트롤러
public class OrderController {
    private final CreateOrderUseCase createOrderUseCase;
    private final ConfirmOrderUseCase confirmOrderUseCase;

    // 주문 생성
    @PostMapping
    public ResponseEntity<ApiResponse<OrderView>> create(
        @XUserId long userId,
        @RequestBody OrderApiDto.CreateRequest request
    ) {
        OrderView order = OrderView.from(createOrderUseCase.execute(request.toCommand(userId)));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(order));
    }

    // 주문 확정
    @PostMapping("/{orderId}/confirm")
    public ApiResponse<OrderView> confirm(@PathVariable long orderId) {
        RequestInputValidator.requirePositiveId(orderId, "주문 ID");
        ConfirmOrderResult result = confirmOrderUseCase.execute(new ConfirmOrderCommand(orderId));
        return ApiResponse.success(OrderView.of(result.order(), result.paymentAmount(), result.paymentStatus()));
    }
}
