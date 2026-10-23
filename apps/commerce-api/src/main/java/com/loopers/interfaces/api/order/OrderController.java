package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderFacade;
import com.loopers.application.payment.dto.PaymentAttemptInfo;
import com.loopers.application.payment.dto.PaymentCheckInfo;
import com.loopers.application.payment.PaymentResultCheckFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import com.loopers.interfaces.api.Requester;
import com.loopers.support.paging.PageRequests;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    private final OrderFacade orderFacade;
    private final PaymentResultCheckFacade paymentResultCheckFacade;

    @PostMapping
    @Override
    public ApiResponse<OrderDto.OrderResponse> create(
        @Requester Long userId,
        @Valid @RequestBody OrderDto.CreateRequest request
    ) {
        return ApiResponse.success(OrderDto.OrderResponse.from(orderFacade.create(userId, request.toCommands())));
    }

    @PostMapping("/{orderId}/payment/reconcile")
    @Override
    public ResponseEntity<ApiResponse<OrderDto.PaymentStartResponse>> reconcile(
        @Requester Long userId,
        @PathVariable Long orderId
    ) {
        PaymentCheckInfo result = paymentResultCheckFacade.checkMyPaymentResult(userId, orderId);
        HttpStatus status = result.info().settled() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(ApiResponse.success(OrderDto.PaymentStartResponse.from(result)));
    }

    @GetMapping
    @Override
    public ApiResponse<PageResponse<OrderDto.OrderSummaryResponse>> getList(
        @Requester Long userId,
        @RequestParam(defaultValue = PageRequests.DEFAULT_PAGE) int page,
        @RequestParam(defaultValue = PageRequests.DEFAULT_SIZE) int size
    ) {
        return ApiResponse.success(
            PageResponse.from(orderFacade.getMyList(userId, page, size), OrderDto.OrderSummaryResponse::from)
        );
    }

    @GetMapping("/{orderId}")
    @Override
    public ApiResponse<OrderDto.OrderResponse> get(
        @Requester Long userId,
        @PathVariable Long orderId
    ) {
        return ApiResponse.success(OrderDto.OrderResponse.from(orderFacade.getMine(userId, orderId)));
    }

    @PostMapping("/{orderId}/confirm")
    @Override
    public ResponseEntity<ApiResponse<OrderDto.PaymentStartResponse>> confirm(
        @Requester Long userId,
        @PathVariable Long orderId,
        @Valid @RequestBody OrderDto.ConfirmRequest request
    ) {
        PaymentAttemptInfo info = orderFacade.confirm(request.toCommand(userId, orderId));
        // PG 결과로 끝난 시도는 200, 처리 중이거나 결과 미확인이면 202. 202 는 성공도 실패도 아니라 아직 모른다는 뜻이다.
        HttpStatus status = info.settled() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(ApiResponse.success(OrderDto.PaymentStartResponse.from(info)));
    }
}
