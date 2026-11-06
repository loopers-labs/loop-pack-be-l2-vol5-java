package com.loopers.application.payment;

import com.loopers.domain.payment.PaymentAttemptModel;
import com.loopers.domain.payment.PaymentAttemptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
@Component
public class PaymentReconciliationScheduler {

    private final PaymentAttemptService paymentAttemptService;
    private final PgPaymentPort pgPaymentPort;
    private final PaymentProcessingService paymentProcessingService;

    @Scheduled(fixedDelay = 600_000)
    public void reconcilePendingPayments() {
        ZonedDateTime threshold = ZonedDateTime.now().minusMinutes(10);
        List<PaymentAttemptModel> pending = paymentAttemptService.findPendingBefore(threshold);

        if (pending.isEmpty()) {
            return;
        }

        log.info("대사 배치 시작: PENDING 건수={}", pending.size());

        for (PaymentAttemptModel attempt : pending) {
            try {
                reconcile(attempt);
            } catch (Exception e) {
                log.error("대사 배치 처리 실패: orderId={}", attempt.getOrderId(), e);
            }
        }
    }

    private void reconcile(PaymentAttemptModel attempt) {
        List<PgPaymentResult> results;
        try {
            results = pgPaymentPort.getByProviderOrderId(attempt.getProviderOrderId());
        } catch (Exception e) {
            log.warn("PG 대사 조회 실패 (CB open 또는 네트워크): orderId={}", attempt.getOrderId());
            return;
        }

        PgPaymentResult conclusive = results.stream()
            .filter(r -> r.isSuccess() || r.isFailed())
            .findFirst()
            .orElse(null);

        if (conclusive == null) {
            log.info("PG 응답 미확정, 건너뜀: orderId={}", attempt.getOrderId());
            return;
        }

        paymentProcessingService.applyPaymentResult(attempt.getOrderId(), conclusive);
        log.info("대사 완료: orderId={}, pgStatus={}", attempt.getOrderId(), conclusive.status());
    }
}
