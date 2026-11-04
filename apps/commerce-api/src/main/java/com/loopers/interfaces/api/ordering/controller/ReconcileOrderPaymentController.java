package com.loopers.interfaces.api.ordering.controller;

import com.loopers.application.ordering.command.ReconcileOrderPaymentCommand;
import com.loopers.application.ordering.query.AdminOrderView;
import com.loopers.application.ordering.query.OrderView;
import com.loopers.application.ordering.result.ConfirmOrderResult;
import com.loopers.application.ordering.usecase.ReconcileOrderPaymentUseCase;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.support.RequestInputValidator;
import com.loopers.interfaces.api.support.XUserId;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ReconcileOrderPaymentController {
    private final ReconcileOrderPaymentUseCase reconcile;

    @PostMapping("/api/v1/orders/{orderId}/payment/reconcile")
    public ResponseEntity<ApiResponse<OrderView>> customer(@XUserId long userId, @PathVariable long orderId) {
        RequestInputValidator.requirePositiveId(orderId, "주문 ID");
        ConfirmOrderResult result = reconcile.execute(new ReconcileOrderPaymentCommand(userId, orderId, false));
        return ResponseEntity.status(status(result)).body(ApiResponse.success(OrderView.from(result)));
    }

    @PostMapping("/api-admin/v1/orders/{orderId}/payment/reconcile")
    public ResponseEntity<ApiResponse<AdminOrderView>> administrator(@XUserId long userId, @PathVariable long orderId) {
        RequestInputValidator.requirePositiveId(orderId, "주문 ID");
        ConfirmOrderResult result = reconcile.execute(new ReconcileOrderPaymentCommand(userId, orderId, true));
        return ResponseEntity.status(status(result)).body(ApiResponse.success(AdminOrderView.from(result)));
    }

    private HttpStatus status(ConfirmOrderResult result) { return result.completed() ? HttpStatus.OK : HttpStatus.ACCEPTED; }
}
