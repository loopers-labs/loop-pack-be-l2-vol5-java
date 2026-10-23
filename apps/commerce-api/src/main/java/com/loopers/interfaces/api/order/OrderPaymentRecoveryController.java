package com.loopers.interfaces.api.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.application.order.OrderPaymentQueryService;
import com.loopers.application.order.OrderPaymentRecoveryService;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.commerce.InvalidRequestException;
import com.loopers.interfaces.api.commerce.StrictInput;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public class OrderPaymentRecoveryController {
    private final OrderPaymentRecoveryService recovery;
    private final OrderPaymentQueryService queries;

    public OrderPaymentRecoveryController(OrderPaymentRecoveryService recovery, OrderPaymentQueryService queries) {
        this.recovery = recovery;
        this.queries = queries;
    }

    @PostMapping("/api/v1/orders/{orderId}/payment/reconcile")
    public ResponseEntity<ApiResponse<OrderPaymentResponse>> reconcile(
        @RequestHeader(value = "X-USER-ID", required = false) String requester, @PathVariable String orderId,
        @RequestBody(required = false) JsonNode body) {
        long id = StrictInput.id(orderId);
        // Ownership before semantic body validation; the transactional service repeats it under the order lock.
        queries.getDetail(requester, id);
        if (body != null && (!body.isObject() || !body.isEmpty())) {
            throw new InvalidRequestException();
        }
        recovery.reconcile(requester, id);
        var response = OrderPaymentResponse.from(queries.getDetail(requester, id));
        return ResponseEntity.status(response.confirmHttpStatus()).body(ApiResponse.success(response));
    }

    @PostMapping(value = "/api/v1/payments/callback", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<CallbackAck> callback(@RequestBody JsonNode body) {
        var notification = PaymentCallbackRequest.from(body);
        recovery.callback(notification.providerOrderId(), notification.transactionKey());
        return ApiResponse.success(new CallbackAck(true));
    }

    /** Means the authoritative notification handling completed, not that the payment succeeded. */
    public record CallbackAck(boolean acknowledged) {
    }
}
