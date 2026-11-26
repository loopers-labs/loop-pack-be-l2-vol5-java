package com.loopers.domains.payment.app;

import com.loopers.domains.payment.app.port.PaymentRefundCaseRepository;
import com.loopers.domains.payment.app.port.RefundOutcomeRecorder;
import java.time.Clock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 조회 결과 반영이 예외로 rollback되면 그 트랜잭션에는 원인을 남길 수 없다. 별도 짧은 트랜잭션으로 마지막 확인 칸만 남긴다(PL P3).
@Component
public class RefundCheckFailureRecorder {
    private final PaymentRefundCaseRepository paymentRefundCaseRepository;
    private final RefundOutcomeRecorder refundOutcomeRecorder;
    private final Clock clock;

    public RefundCheckFailureRecorder(PaymentRefundCaseRepository paymentRefundCaseRepository,
        RefundOutcomeRecorder refundOutcomeRecorder, Clock clock) {
        this.paymentRefundCaseRepository = paymentRefundCaseRepository;
        this.refundOutcomeRecorder = refundOutcomeRecorder;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordApplyFailed(long orderId, String causeType) {
    }
}
