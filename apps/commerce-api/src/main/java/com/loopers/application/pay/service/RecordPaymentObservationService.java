package com.loopers.application.pay.service;

import com.loopers.application.pay.usecase.RecordPaymentObservationUseCase;
import com.loopers.application.support.error.ApplicationErrorCode;
import com.loopers.application.support.error.ApplicationException;
import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentObservation;
import com.loopers.domain.pay.repository.PaymentAttemptRepository;
import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RecordPaymentObservationService implements RecordPaymentObservationUseCase {
    private final PaymentAttemptRepository repository;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentAttempt execute(String attemptKey, PaymentObservation observation) {
        Objects.requireNonNull(observation);
        PaymentAttempt current = repository.findByKeyForUpdate(attemptKey)
                .orElseThrow(() -> new ApplicationException(ApplicationErrorCode.PAYMENT_ATTEMPT_NOT_FOUND));
        if ((observation.kind() == PaymentObservation.Kind.VERIFIED
                || observation.kind() == PaymentObservation.Kind.REJECTED_NO_EFFECT)
                && current.getDispatchState() != PaymentAttempt.DispatchState.STARTED) {
            throw new DomainException(DomainErrorCode.PAYMENT_OBSERVATION_NOT_ALLOWED);
        }
        PaymentAttempt changed = switch (observation.kind()) {
            case VERIFIED -> current.observe(observation.providerOrderId(), observation.amount(), observation.transactionKey(),
                    PaymentAttempt.Outcome.valueOf(observation.status().name()));
            case REJECTED_NO_EFFECT -> current.rejectBeforeCreate(observation.providerOrderId(), observation.amount());
            case CONFLICT -> current.noteConflict(PaymentAttempt.Conflict.IDENTITY_MISMATCH);
            case NOT_FOUND, UNAVAILABLE -> current;
        };
        return changed == current ? current : repository.save(changed);
    }
}
