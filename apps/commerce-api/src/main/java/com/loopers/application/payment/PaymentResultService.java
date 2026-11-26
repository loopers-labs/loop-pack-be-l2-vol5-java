package com.loopers.application.payment;

import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// PG 관찰을 결제 시도·주문·재고에 한 번만 반영한다.
// PG 조회는 트랜잭션 밖에서 하고, 반영(TX B)·0행 판정·응답용 재조회는 각각 새 트랜잭션에서 수행한다.
// 바깥 트랜잭션에 합류하면 이 경계가 무너지므로 트랜잭션 안에서 호출하면 거절한다.
@Slf4j
@RequiredArgsConstructor
@Component
public class PaymentResultService {
    private final PaymentGateway paymentGateway;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentResultTransaction paymentResultTransaction;

    // 결제 요청이 받은 생성 응답은 PG를 다시 조회하지 않고 반영한다.
    @Transactional(propagation = Propagation.NEVER)
    public PaymentReflection applyCreateResponse(Long attemptId, PaymentCreateObservation observation) {
        return reflect(attemptId, switch (observation) {
            case PaymentCreateObservation.Accepted accepted -> new PaymentObservation.Accepted(accepted.transactionKey());
            case PaymentCreateObservation.Unknown ignored -> new PaymentObservation.Unknown();
            case PaymentCreateObservation.RejectedNoEffect ignored -> new PaymentObservation.RejectedBeforeCreate();
        });
    }

    // 미종결 시도의 기존 거래를 저장된 거래 키나 PG 주문 식별자로 조회해 반영한다. 생성 요청은 보내지 않는다.
    @Transactional(propagation = Propagation.NEVER)
    public PaymentReflection check(Long attemptId) {
        PaymentAttempt attempt = paymentAttemptRepository.findById(attemptId)
            .orElseThrow(() -> new CoreException(ErrorType.INTERNAL_ERROR, "결제 시도가 없습니다."));
        if (attempt.isTerminal()) {
            return new PaymentReflection(PaymentReflectionOutcome.UNCHANGED, paymentResultTransaction.read(attemptId));
        }
        PaymentLookupObservation lookup = attempt.getTransactionKey() != null
            ? paymentGateway.lookup(attempt.getTransactionKey())
            : paymentGateway.lookupByOrder(attempt.getProviderOrderId());
        logLookup(attempt, lookup);
        return applyLookup(attemptId, lookup);
    }

    // 거래 0건·404·조회 불가는 종결과 재고 반환의 근거가 아니므로 TX B를 열지 않는다.
    @Transactional(propagation = Propagation.NEVER)
    public PaymentReflection applyLookup(Long attemptId, PaymentLookupObservation lookup) {
        return switch (lookup) {
            case PaymentLookupObservation.Unavailable ignored ->
                new PaymentReflection(PaymentReflectionOutcome.LOOKUP_UNAVAILABLE, paymentResultTransaction.read(attemptId));
            case PaymentLookupObservation.NotFound ignored ->
                new PaymentReflection(PaymentReflectionOutcome.UNCHANGED, paymentResultTransaction.read(attemptId));
            case PaymentLookupObservation.Found found when found.transactions().isEmpty() ->
                new PaymentReflection(PaymentReflectionOutcome.UNCHANGED, paymentResultTransaction.read(attemptId));
            case PaymentLookupObservation.Found found when found.transactions().size() > 1 ->
                reflect(attemptId, new PaymentObservation.MultipleTransactions());
            case PaymentLookupObservation.Found found -> reflect(attemptId, transaction(found.transactions().getFirst()));
        };
    }

    // 저장된 결제 상태를 새 읽기 트랜잭션에서 읽는다.
    @Transactional(propagation = Propagation.NEVER)
    public PaymentStatusInfo read(Long attemptId) {
        return paymentResultTransaction.read(attemptId);
    }

    // 반영 단계의 저장 오류는 rollback으로 알리고, 그 뒤 응답용 재조회 실패는 그대로 전달해 호출 경계가 구분하게 한다.
    private PaymentReflection reflect(Long attemptId, PaymentObservation observation) {
        PaymentReflectionOutcome outcome;
        try {
            outcome = paymentResultTransaction.apply(attemptId, observation);
            if (outcome == PaymentReflectionOutcome.UNCHANGED) {
                outcome = paymentResultTransaction.explainUnchanged(attemptId, observation);
            }
        } catch (CoreException integrityError) {
            throw integrityError;
        } catch (RuntimeException storageError) {
            throw new PaymentReflectionRolledBackException(storageError);
        }
        return new PaymentReflection(outcome, paymentResultTransaction.read(attemptId));
    }

    private static PaymentObservation transaction(PgTransaction transaction) {
        PaymentAttemptStatus status = switch (transaction.status()) {
            case PENDING -> PaymentAttemptStatus.PENDING;
            case SUCCESS -> PaymentAttemptStatus.SUCCEEDED;
            case FAILED -> PaymentAttemptStatus.FAILED;
        };
        return new PaymentObservation.Transaction(transaction.transactionKey(), transaction.providerOrderId(),
            transaction.amount(), status);
    }

    // 조회 불가는 callback과 주기 조회가 기존 warn을 함께 남기고 reconcile은 응답으로 알리므로, 여기서는 INFO로 남긴다.
    // 콘솔에서도 찾을 수 있게 문장에 시도·주문 ID를 넣는다.
    private void logLookup(PaymentAttempt attempt, PaymentLookupObservation lookup) {
        LoggingEventBuilder event = log.atInfo()
            .addKeyValue("event", "payment.lookup.observed")
            .addKeyValue("attemptId", attempt.getId())
            .addKeyValue("orderId", attempt.getOrderId());
        switch (lookup) {
            case PaymentLookupObservation.Found found -> event
                .addKeyValue("outcome", "FOUND")
                .addKeyValue("pgStatus", found.transactions().size() == 1 ? found.transactions().getFirst().status() : null)
                .addKeyValue("transactionCount", found.transactions().size())
                .log("PG 거래를 조회했습니다. attemptId={}, orderId={}, transactionCount={}",
                    attempt.getId(), attempt.getOrderId(), found.transactions().size());
            case PaymentLookupObservation.NotFound ignored -> event
                .addKeyValue("outcome", "NOT_FOUND")
                .addKeyValue("transactionCount", 0)
                .log("PG 거래를 찾지 못했습니다. attemptId={}, orderId={}", attempt.getId(), attempt.getOrderId());
            case PaymentLookupObservation.Unavailable unavailable -> event
                .addKeyValue("outcome", "UNAVAILABLE")
                .addKeyValue("reason", unavailable.kind())
                .log("PG 거래를 조회하지 못했습니다. attemptId={}, orderId={}, reason={}",
                    attempt.getId(), attempt.getOrderId(), unavailable.kind());
        }
    }
}
