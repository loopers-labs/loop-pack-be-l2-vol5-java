package com.loopers.domains.order.in;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.common.in.ApiResponse;
import com.loopers.common.in.RequestValues;
import com.loopers.domains.payment.app.RefundPersonResolutionService;
import java.security.Principal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// 관리자 API의 오류 응답 계약(CommerceExceptionAdvice: 404·409·400)을 같이 쓰도록 다른 관리자 API와 같은 order.in에 둔다.
@RestController
public class AdminRefundCaseController {
    private final RefundPersonResolutionService refundPersonResolutionService;

    public AdminRefundCaseController(RefundPersonResolutionService refundPersonResolutionService) {
        this.refundPersonResolutionService = refundPersonResolutionService;
    }

    @PostMapping("/api-admin/v1/payments/refund-cases/{orderId}/person-resolution")
    public ApiResponse<RefundPersonResolutionService.Resolution> resolve(@PathVariable long orderId,
        @RequestBody PersonResolutionRequest request, Principal principal) {
        return ApiResponse.success(refundPersonResolutionService.resolve(orderId, RequestValues.name(request.result()),
            principal.getName()));
    }

    public record PersonResolutionRequest(JsonNode result) { }
}
