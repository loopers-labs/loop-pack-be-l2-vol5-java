package com.loopers.application.payment;

import com.loopers.domain.payment.PaymentCheckToken;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.persistence.ConstraintViolationClassifier;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class PaymentObservationApplier {

    private final PaymentTransactionService transactions;
    private final ConstraintViolationClassifier constraints;

    public PaymentApplyResult apply(PaymentCheckToken token, PaymentObservation observation) {
        PaymentApplyResult result;
        try {
            result = transactions.applyObservation(token, observation);
        } catch (DataIntegrityViolationException failure) {
            if (!constraints.namedUniqueViolation(failure).filter("uk_payment_transaction"::equals).isPresent()
                || !(observation instanceof PaymentObservation.TransactionObserved transaction)) {
                throw failure;
            }
            // 앞선 프록시 TX의 rollback이 끝난 다음 별도 TX로 충돌을 보존한다.
            result = transactions.recordConstraintConflict(token, transaction);
        }
        if (result.outcome() == PaymentApplyResult.Outcome.CONFLICT) {
            throw new CoreException(ErrorType.PAYMENT_RESULT_CONFLICT);
        }
        return result;
    }
}
