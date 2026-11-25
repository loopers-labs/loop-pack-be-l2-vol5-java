package com.loopers.domains.order.in;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.common.in.ApiResponse;
import com.loopers.domains.order.app.PaymentReconcileBatchService;
import com.loopers.domains.order.app.ReconcileBatchResult;
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
    public ApiResponse<ReconcileBatchResult> reconcile(@RequestBody ReconcileBatchRequest request) {
        return ApiResponse.success(paymentReconcileBatchService.reconcile(List.of()));
    }

    public record ReconcileBatchRequest(JsonNode orderIds) { }
}
