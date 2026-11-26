package com.loopers.domains.order.in;

import com.loopers.common.in.ApiResponse;
import com.loopers.common.in.RequestValues;
import com.loopers.domains.order.app.UnresolvedItem;
import com.loopers.domains.order.app.UnresolvedPaymentQueryService;
import com.loopers.domains.order.app.UnresolvedPaymentView;
import com.loopers.domains.order.app.UnresolvedRefundCaseView;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminUnresolvedPaymentController {
    private final UnresolvedPaymentQueryService unresolvedPaymentQueryService;

    public AdminUnresolvedPaymentController(UnresolvedPaymentQueryService unresolvedPaymentQueryService) {
        this.unresolvedPaymentQueryService = unresolvedPaymentQueryService;
    }

    // status가 HANDED_TO_HUMAN·REFUND_UNKNOWN이면 환불 확인 대상 모양, 아니면 결제 대기 PG 결제 모양의 항목을 준다(P7).
    @GetMapping("/api-admin/v1/payments/unresolved")
    public ApiResponse<List<Object>> list(@RequestParam(required = false) List<String> status,
        @RequestParam(defaultValue = "0") long minAgeSeconds, @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
        RequestValues.page(page, size);
        return ApiResponse.success(unresolvedPaymentQueryService.list(status, minAgeSeconds, page, size).stream()
            .map(AdminUnresolvedPaymentController::response)
            .toList());
    }

    private static Object response(UnresolvedItem item) {
        return switch (item) {
            case UnresolvedPaymentView payment -> UnresolvedPaymentResponse.from(payment);
            case UnresolvedRefundCaseView refundCase -> UnresolvedRefundCaseResponse.from(refundCase);
        };
    }
}
