package com.loopers.interfaces.api.admin.payment;

import com.loopers.domain.payment.PaymentDlqModel;
import com.loopers.domain.payment.PaymentDlqService;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/payment-dlq")
public class PaymentDlqAdminV1Controller {

    private final PaymentDlqService paymentDlqService;

    @GetMapping
    public ApiResponse<List<PaymentDlqAdminV1Dto.DlqEntry>> getDlqEntries(
        @RequestParam(defaultValue = "false") boolean resolved
    ) {
        List<PaymentDlqModel> entries = resolved
            ? paymentDlqService.findAll()
            : paymentDlqService.findUnresolved();
        return ApiResponse.success(entries.stream()
            .map(PaymentDlqAdminV1Dto.DlqEntry::from)
            .toList());
    }

    @GetMapping("/{id}")
    public ApiResponse<PaymentDlqAdminV1Dto.DlqEntry> getDlqEntry(@PathVariable Long id) {
        return ApiResponse.success(PaymentDlqAdminV1Dto.DlqEntry.from(paymentDlqService.getById(id)));
    }

    @PostMapping("/{id}/resolve")
    public ApiResponse<PaymentDlqAdminV1Dto.DlqEntry> resolve(
        @PathVariable Long id,
        @RequestBody PaymentDlqAdminV1Dto.ResolveRequest request
    ) {
        PaymentDlqModel resolved = paymentDlqService.resolve(id, request.note());
        return ApiResponse.success(PaymentDlqAdminV1Dto.DlqEntry.from(resolved));
    }
}
