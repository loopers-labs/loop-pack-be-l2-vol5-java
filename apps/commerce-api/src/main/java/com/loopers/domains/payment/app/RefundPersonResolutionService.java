package com.loopers.domains.payment.app;

import com.loopers.domains.payment.app.port.PaymentRefundCaseRepository;
import com.loopers.domains.payment.app.port.RefundOutcomeRecorder;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;

// 골격(w10s5b S5 Red): 사람 담당 중인 환불 확인 대상에 사람이 확인한 결론을 적는다. 업무 로직은 Green에서 넣는다.
@Service
public class RefundPersonResolutionService {
    private final PaymentRefundCaseRepository paymentRefundCaseRepository;
    private final RefundOutcomeRecorder refundOutcomeRecorder;
    private final Clock clock;

    public RefundPersonResolutionService(PaymentRefundCaseRepository paymentRefundCaseRepository,
        RefundOutcomeRecorder refundOutcomeRecorder, Clock clock) {
        this.paymentRefundCaseRepository = paymentRefundCaseRepository;
        this.refundOutcomeRecorder = refundOutcomeRecorder;
        this.clock = clock;
    }

    public Resolution resolve(long orderId, String result, String adminName) {
        return null;
    }

    public record Resolution(long orderId, String moneyStatus, String refundActor, String owner, Instant ownerClosedAt,
        String ownerClosedBy) { }
}
