package com.loopers.domains.payment.app;

import com.loopers.common.error.InvalidValueException;
import com.loopers.domains.payment.app.port.PaymentRefundCaseRepository;
import com.loopers.domains.payment.app.port.RefundOutcomeRecorder;
import com.loopers.domains.payment.domain.RefundCase;
import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// 사람 담당 중인 환불 확인 대상에 사람이 확인한 결론(PG로 확인되지 않는 것)을 적는다(10주차 5b 계획 5절, Q1).
// 사실 기록만 한다: PG 호출·재결제·주문 변경은 없다. 추적 회차·늦은 성공 합류와 같은 대상 행 잠금 아래에서 바꾼다.
@Slf4j
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

    @Transactional
    public Resolution resolve(long orderId, String result, String adminName) {
        RefundCase.PersonResult personResult = personResult(result);
        RefundCase refundCase = paymentRefundCaseRepository.findByOrderIdForUpdate(orderId)
            .orElseThrow(RefundCaseNotFoundException::new);
        refundCase.resolveByPerson(personResult, adminName, clock.instant());
        paymentRefundCaseRepository.save(refundCase);
        RefundOutcomeRecorder.Result outcome = personResult == RefundCase.PersonResult.REFUNDED
            ? RefundOutcomeRecorder.Result.REFUNDED : RefundOutcomeRecorder.Result.NO_CHARGE;
        afterCommit(() -> {
            refundOutcomeRecorder.recordOutcome(outcome, RefundCase.RefundActor.PERSON);
            log.info("환불 확인 대상에 사람 결론을 기록했습니다. orderId={}, result={}", orderId, outcome);
        });
        return new Resolution(refundCase.getOrderId(), refundCase.getMoneyStatus().name(),
            refundCase.getRefundActor() == null ? null : refundCase.getRefundActor().name(), refundCase.getOwner().name(),
            refundCase.getOwnerClosedAt(), refundCase.getOwnerClosedBy());
    }

    // 대소문자를 바꿔 받지 않는다. 운영 기록이라 정확한 값만 받는다.
    private static RefundCase.PersonResult personResult(String result) {
        for (RefundCase.PersonResult candidate : RefundCase.PersonResult.values()) {
            if (candidate.name().equals(result)) {
                return candidate;
            }
        }
        throw new InvalidValueException("사람 기록 결과는 REFUNDED 또는 NO_CHARGE여야 합니다.");
    }

    // rollback된 기록을 세지 않도록 commit 뒤에만 센다.
    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    public record Resolution(long orderId, String moneyStatus, String refundActor, String owner, Instant ownerClosedAt,
        String ownerClosedBy) { }
}
