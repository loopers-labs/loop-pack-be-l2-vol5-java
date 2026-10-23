package com.loopers.domain.pay.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.domain.pay.model.PaymentObservation;
import com.loopers.domain.pay.model.PaymentRecoveryTask;
import com.loopers.domain.pay.model.ProviderPaymentStatus;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PaymentLookupPolicyTest {
    @ParameterizedTest
    @MethodSource("observations")
    @DisplayName("결제 결과와 조회 회로 집계·재조회·중단 사유를 구분한다")
    void classifies(PaymentObservation observation, PaymentLookupPolicy.Circuit circuit,
                    boolean retryable, PaymentRecoveryTask.Reason reason) {
        assertThat(PaymentLookupPolicy.circuit(observation)).isEqualTo(circuit);
        assertThat(PaymentLookupPolicy.retryable(observation)).isEqualTo(retryable);
        assertThat(PaymentLookupPolicy.incompleteReason(observation)).isEqualTo(reason);
    }

    static Stream<Arguments> observations() {
        Stream<Arguments> statuses = Stream.of(408, 429, 500, 502, 503, 504)
            .map(status -> row(PaymentObservation.httpFailure(status), PaymentLookupPolicy.Circuit.FAILURE,
                true, PaymentRecoveryTask.Reason.LOOKUP_UNAVAILABLE));
        Stream<Arguments> contract = Stream.of(202, 204, 302, 501, 505)
            .map(status -> row(PaymentObservation.httpFailure(status), PaymentLookupPolicy.Circuit.FAILURE,
                false, PaymentRecoveryTask.Reason.LOOKUP_CONTRACT_ERROR));
        Stream<Arguments> request = Stream.of(400, 401, 403, 404, 409, 422)
            .map(status -> row(PaymentObservation.httpFailure(status), PaymentLookupPolicy.Circuit.EXCLUDED,
                false, PaymentRecoveryTask.Reason.LOOKUP_REQUEST_ERROR));
        Stream<Arguments> other = Stream.of(
            row(verified(ProviderPaymentStatus.SUCCESS), PaymentLookupPolicy.Circuit.NORMAL, false, PaymentRecoveryTask.Reason.NONE),
            row(verified(ProviderPaymentStatus.FAILED), PaymentLookupPolicy.Circuit.NORMAL, false, PaymentRecoveryTask.Reason.NONE),
            row(verified(ProviderPaymentStatus.PENDING), PaymentLookupPolicy.Circuit.NORMAL, true, PaymentRecoveryTask.Reason.PENDING),
            row(PaymentObservation.missing(), PaymentLookupPolicy.Circuit.NORMAL, true, PaymentRecoveryTask.Reason.LOOKUP_UNAVAILABLE),
            row(PaymentObservation.unavailable(PaymentObservation.Reason.TIMEOUT), PaymentLookupPolicy.Circuit.FAILURE, true, PaymentRecoveryTask.Reason.LOOKUP_UNAVAILABLE),
            row(PaymentObservation.unavailable(PaymentObservation.Reason.TRANSPORT_ERROR), PaymentLookupPolicy.Circuit.FAILURE, true, PaymentRecoveryTask.Reason.LOOKUP_UNAVAILABLE),
            row(PaymentObservation.unavailable(PaymentObservation.Reason.INVALID_RESPONSE), PaymentLookupPolicy.Circuit.FAILURE, false, PaymentRecoveryTask.Reason.LOOKUP_CONTRACT_ERROR),
            row(PaymentObservation.unavailable(PaymentObservation.Reason.CIRCUIT_BLOCKED), PaymentLookupPolicy.Circuit.EXCLUDED, false, PaymentRecoveryTask.Reason.CIRCUIT_BLOCKED),
            row(PaymentObservation.conflict(PaymentObservation.Reason.IDENTITY_MISMATCH), PaymentLookupPolicy.Circuit.EXCLUDED, false, PaymentRecoveryTask.Reason.IDENTITY_CONFLICT),
            row(PaymentObservation.conflict(PaymentObservation.Reason.MULTIPLE_TRANSACTIONS), PaymentLookupPolicy.Circuit.EXCLUDED, false, PaymentRecoveryTask.Reason.IDENTITY_CONFLICT));
        return Stream.of(statuses, contract, request, other).flatMap(stream -> stream);
    }

    private static Arguments row(PaymentObservation observation, PaymentLookupPolicy.Circuit circuit,
                                 boolean retryable, PaymentRecoveryTask.Reason reason) {
        return Arguments.of(observation, circuit, retryable, reason);
    }

    private static PaymentObservation verified(ProviderPaymentStatus status) {
        return PaymentObservation.verified("PG-1", "LP-ORD-0000000042", 7000, status);
    }
}
