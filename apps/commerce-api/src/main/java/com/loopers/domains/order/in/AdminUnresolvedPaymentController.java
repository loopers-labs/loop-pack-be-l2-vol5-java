package com.loopers.domains.order.in;

import com.loopers.common.in.ApiResponse;
import com.loopers.common.in.RequestValues;
import com.loopers.domains.order.app.UnresolvedPaymentQueryService;
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

    @GetMapping("/api-admin/v1/payments/unresolved")
    public ApiResponse<List<UnresolvedPaymentResponse>> list(@RequestParam(required = false) List<String> status,
        @RequestParam(defaultValue = "0") long minAgeSeconds, @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
        RequestValues.page(page, size);
        return ApiResponse.success(unresolvedPaymentQueryService.list(status, minAgeSeconds, page, size).stream()
            .map(UnresolvedPaymentResponse::from)
            .toList());
    }
}
