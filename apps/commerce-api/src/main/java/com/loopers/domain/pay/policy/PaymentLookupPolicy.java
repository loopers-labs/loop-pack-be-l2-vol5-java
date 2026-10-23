package com.loopers.domain.pay.policy;

import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentObservation;
import com.loopers.domain.pay.model.PaymentRecoveryTask;
import com.loopers.domain.pay.model.ProviderPaymentStatus;
import java.util.Objects;

/** 조회 기능의 정상 여부와 결제를 다시 확인할 필요를 별도로 판단한다. */
public final class PaymentLookupPolicy {
    public enum Circuit { NORMAL, FAILURE, EXCLUDED }

    private PaymentLookupPolicy() {}

    public static Circuit circuit(PaymentObservation observation, PaymentAttempt.Outcome outcome,
                                  PaymentAttempt.Rejection rejection) {
        Objects.requireNonNull(observation);
        Objects.requireNonNull(outcome);
        Objects.requireNonNull(rejection);
        if (observation.kind() == PaymentObservation.Kind.VERIFIED) {
            if (rejection == PaymentAttempt.Rejection.NO_EFFECT) { return Circuit.EXCLUDED; }
            boolean oppositeTerminal = (outcome == PaymentAttempt.Outcome.SUCCESS
                && observation.status() == ProviderPaymentStatus.FAILED)
                || (outcome == PaymentAttempt.Outcome.FAILED && observation.status() == ProviderPaymentStatus.SUCCESS);
            if (oppositeTerminal) { return Circuit.EXCLUDED; }
        }
        return circuit(observation);
    }

    public static Circuit circuit(PaymentObservation observation) {
        Objects.requireNonNull(observation);
        return switch (observation.kind()) {
            case VERIFIED, NOT_FOUND -> Circuit.NORMAL;
            case CONFLICT, REJECTED_NO_EFFECT -> Circuit.EXCLUDED;
            case UNAVAILABLE -> switch (observation.reason()) {
                case CIRCUIT_BLOCKED -> Circuit.EXCLUDED;
                case HTTP_ERROR -> isRequestError(observation) ? Circuit.EXCLUDED : Circuit.FAILURE;
                default -> Circuit.FAILURE;
            };
        };
    }

    public static boolean retryable(PaymentObservation observation) {
        Objects.requireNonNull(observation);
        return switch (observation.kind()) {
            case VERIFIED -> observation.status() == ProviderPaymentStatus.PENDING;
            case NOT_FOUND -> true;
            case CONFLICT, REJECTED_NO_EFFECT -> false;
            case UNAVAILABLE -> switch (observation.reason()) {
                case TIMEOUT, TRANSPORT_ERROR -> true;
                case HTTP_ERROR -> retryableStatus(observation.httpStatus());
                default -> false;
            };
        };
    }

    public static PaymentRecoveryTask.Reason incompleteReason(PaymentObservation observation) {
        Objects.requireNonNull(observation);
        if (observation.kind() == PaymentObservation.Kind.CONFLICT) {
            return PaymentRecoveryTask.Reason.IDENTITY_CONFLICT;
        }
        if (observation.kind() == PaymentObservation.Kind.VERIFIED) {
            return observation.status() == ProviderPaymentStatus.PENDING
                ? PaymentRecoveryTask.Reason.PENDING : PaymentRecoveryTask.Reason.NONE;
        }
        if (observation.reason() == PaymentObservation.Reason.CIRCUIT_BLOCKED) {
            return PaymentRecoveryTask.Reason.CIRCUIT_BLOCKED;
        }
        if (retryable(observation)) { return PaymentRecoveryTask.Reason.LOOKUP_UNAVAILABLE; }
        return isRequestError(observation) ? PaymentRecoveryTask.Reason.LOOKUP_REQUEST_ERROR
            : PaymentRecoveryTask.Reason.LOOKUP_CONTRACT_ERROR;
    }

    private static boolean isRequestError(PaymentObservation observation) {
        Integer status = observation.httpStatus();
        return observation.reason() == PaymentObservation.Reason.HTTP_ERROR
            && status != null && status >= 400 && status < 500 && status != 408 && status != 429;
    }

    private static boolean retryableStatus(Integer status) {
        return status != null && (status == 408 || status == 429 || status == 500
            || status == 502 || status == 503 || status == 504);
    }
}
