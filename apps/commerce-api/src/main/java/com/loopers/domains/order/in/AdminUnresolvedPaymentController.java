package com.loopers.domains.order.in;

import com.loopers.common.in.ApiResponse;
import com.loopers.domains.order.app.UnresolvedPaymentQueryService;
import com.loopers.domains.order.app.UnresolvedPaymentView;
import java.util.List;
import java.util.Set;
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
    public ApiResponse<List<UnresolvedPaymentView>> list(@RequestParam(required = false) List<String> status,
        @RequestParam(defaultValue = "0") long minAgeSeconds, @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(unresolvedPaymentQueryService.list(Set.of(), minAgeSeconds, page, size));
    }
}
