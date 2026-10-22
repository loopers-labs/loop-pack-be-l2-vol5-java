package com.loopers.interfaces.api.pg;

import com.loopers.application.payment.PaymentProcessingService;
import com.loopers.application.payment.PgPaymentPort;
import com.loopers.application.payment.PgPaymentResult;
import com.loopers.domain.payment.PaymentAttemptModel;
import com.loopers.domain.payment.PaymentAttemptService;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/orders")
public class PaymentCallbackV1Controller {

    private final PaymentAttemptService paymentAttemptService;
    private final PgPaymentPort pgPaymentPort;
    private final PaymentProcessingService paymentProcessingService;

    public record CallbackRequest(String providerOrderId, String transactionKey, String status) {}

    /**
     * PG가 결제 결과를 Push하는 콜백 엔드포인트.
     * 콜백 내용을 그대로 신뢰하지 않고 PG GET으로 검증 후 적용한다.
     */
    @PostMapping("/{orderId}/payment/callback")
    public ApiResponse<Void> handleCallback(
        @PathVariable Long orderId,
        @RequestBody CallbackRequest request
    ) {
        PaymentAttemptModel attempt = paymentAttemptService.getByOrderId(orderId);

        if (attempt.isTerminal()) {
            return ApiResponse.success(null);
        }

        if (attempt.getTransactionKey() == null && request.transactionKey() == null) {
            log.warn("콜백 transactionKey 없음: orderId={}", orderId);
            return ApiResponse.success(null);
        }

        String txKey = request.transactionKey() != null
            ? request.transactionKey()
            : attempt.getTransactionKey();

        PgPaymentResult verified;
        try {
            verified = pgPaymentPort.getByTransactionKey(txKey);
        } catch (Exception e) {
            log.warn("콜백 PG 검증 실패: orderId={}, txKey={}", orderId, txKey, e);
            return ApiResponse.success(null);
        }

        if (verified.isPending() || verified.isUnknown()) {
            return ApiResponse.success(null);
        }

        paymentProcessingService.applyPaymentResult(orderId, verified);
        return ApiResponse.success(null);
    }

    /**
     * 수동 대사 트리거 엔드포인트. PENDING 상태 결제를 PG에 조회해 강제 확정.
     */
    @PostMapping("/{orderId}/payment/reconcile")
    public ApiResponse<Void> reconcile(@PathVariable Long orderId) {
        PaymentAttemptModel attempt = paymentAttemptService.getByOrderId(orderId);

        if (attempt.isTerminal()) {
            return ApiResponse.success(null);
        }

        String providerOrderId = attempt.getProviderOrderId();
        java.util.List<PgPaymentResult> results;
        try {
            results = pgPaymentPort.getByProviderOrderId(providerOrderId);
        } catch (Exception e) {
            log.warn("대사 PG 조회 실패: orderId={}", orderId, e);
            throw new CoreException(ErrorType.INTERNAL_ERROR, "PG 조회에 실패했습니다. 잠시 후 다시 시도해주세요.");
        }

        PgPaymentResult conclusive = results.stream()
            .filter(r -> r.isSuccess() || r.isFailed())
            .findFirst()
            .orElse(null);

        if (conclusive == null) {
            return ApiResponse.success(null);
        }

        paymentProcessingService.applyPaymentResult(orderId, conclusive);
        return ApiResponse.success(null);
    }
}
