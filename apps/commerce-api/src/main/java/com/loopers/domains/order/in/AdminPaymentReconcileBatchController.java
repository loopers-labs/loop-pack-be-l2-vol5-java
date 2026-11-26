package com.loopers.domains.order.in;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.common.in.ApiResponse;
import com.loopers.common.in.RequestValues;
import com.loopers.common.in.error.CoreException;
import com.loopers.common.in.error.ErrorType;
import com.loopers.domains.order.app.PaymentReconcileBatchService;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminPaymentReconcileBatchController {
    private final PaymentReconcileBatchService paymentReconcileBatchService;

    public AdminPaymentReconcileBatchController(PaymentReconcileBatchService paymentReconcileBatchService) {
        this.paymentReconcileBatchService = paymentReconcileBatchService;
    }

    @PostMapping("/api-admin/v1/payments/reconcile-batch")
    public ApiResponse<ReconcileBatchResponse> reconcile(@RequestBody ReconcileBatchRequest request, Principal principal) {
        return ApiResponse.success(ReconcileBatchResponse.from(
            paymentReconcileBatchService.reconcile(principal.getName(), orderIds(request.orderIds()))));
    }

    private static List<Long> orderIds(JsonNode node) {
        if (node == null || !node.isArray()) {
            throw new CoreException(ErrorType.BAD_REQUEST);
        }
        List<Long> orderIds = new ArrayList<>();
        for (JsonNode element : node) {
            long orderId = RequestValues.integer(element);
            if (orderId <= 0) {
                throw new CoreException(ErrorType.BAD_REQUEST);
            }
            orderIds.add(orderId);
        }
        return orderIds;
    }

    public record ReconcileBatchRequest(JsonNode orderIds) { }
}
