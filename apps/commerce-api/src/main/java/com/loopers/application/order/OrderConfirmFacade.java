package com.loopers.application.order;

import com.loopers.application.payment.BeginConfirmationResult;
import com.loopers.application.payment.PaymentApplyResult;
import com.loopers.application.payment.PaymentConfirmCommand;
import com.loopers.application.payment.PaymentObservationApplier;
import com.loopers.application.payment.PaymentTransactionService;
import com.loopers.application.payment.PrepareConfirmationResult;
import com.loopers.domain.payment.CreateAdmission;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.persistence.ConstraintViolationClassifier;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class OrderConfirmFacade {

    private final PaymentTransactionService transactions;
    private final PaymentGateway gateway;
    private final PaymentObservationApplier applier;
    private final ConstraintViolationClassifier constraints;

    public PaymentApplyResult confirm(Long userId, Long orderId, PaymentConfirmCommand command) {
        PrepareConfirmationResult prepared = transactions.prepareConfirmation(userId, orderId, command);
        if (prepared.status() == PrepareConfirmationResult.Status.STORED) {
            return stored(prepared.stored());
        }
        if (gateway.checkCreateAdmission() == CreateAdmission.BLOCKED) {
            prepared = transactions.prepareConfirmation(userId, orderId, command);
            if (prepared.status() == PrepareConfirmationResult.Status.STORED) {
                return stored(prepared.stored());
            }
            throw new CoreException(ErrorType.PG_CREATE_UNAVAILABLE);
        }
        BeginConfirmationResult begin;
        try {
            begin = transactions.beginConfirmation(userId, orderId, command);
        } catch (DataIntegrityViolationException failure) {
            String constraint = constraints.namedUniqueViolation(failure).orElse("");
            if (!"uk_payment_order".equals(constraint) && !"uk_payment_key".equals(constraint)) {
                throw failure;
            }
            prepared = transactions.prepareConfirmation(userId, orderId, command);
            if (prepared.status() == PrepareConfirmationResult.Status.STORED) {
                return stored(prepared.stored());
            }
            throw new CoreException(ErrorType.PAYMENT_ATTEMPT_CONFLICT);
        }
        if (begin.status() == BeginConfirmationResult.Status.STORED) {
            return stored(begin.stored());
        }
        return applier.apply(begin.token(), gateway.create(begin.request()));
    }

    private static PaymentApplyResult stored(OrderInfo order) {
        boolean terminal = "CONFIRMED".equals(order.status()) || "PAYMENT_FAILED".equals(order.status());
        return new PaymentApplyResult(order, terminal ? PaymentApplyResult.Outcome.FINAL : PaymentApplyResult.Outcome.PENDING);
    }
}
