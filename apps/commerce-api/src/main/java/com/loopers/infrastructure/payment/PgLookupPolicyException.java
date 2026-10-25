package com.loopers.infrastructure.payment;

import com.loopers.application.payment.PaymentLookupObservation;

// 로컬 차단과 전체 시간 초과를 회로 바깥에서 저장 상태를 유지하는 관찰로 변환한다.
abstract class PgLookupPolicyException extends RuntimeException {
    private final PaymentLookupObservation.FailureKind kind;

    PgLookupPolicyException(PaymentLookupObservation.FailureKind kind) {
        super(kind.name());
        this.kind = kind;
    }

    PaymentLookupObservation observation() {
        return new PaymentLookupObservation.Unavailable(kind.name(), kind);
    }

    static final class LocalRejection extends PgLookupPolicyException {
        LocalRejection(PaymentLookupObservation.FailureKind kind) { super(kind); }
    }

    static final class DeadlineExceeded extends PgLookupPolicyException {
        DeadlineExceeded() { super(PaymentLookupObservation.FailureKind.DEADLINE_EXCEEDED); }
    }
}
