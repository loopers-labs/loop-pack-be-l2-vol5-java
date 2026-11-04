package com.loopers.application.pay.gateway;

import com.loopers.application.pay.gateway.PaymentProviderGateway.ExpectedPayment;
import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentObservation;
import java.util.Objects;

/** DB TX 밖에서 기존 PG 거래를 한 번 조회한다. 생성·자동 재시도는 포함하지 않는다. */
public interface PaymentLookupGateway {
    PaymentObservation lookup(LookupRequest request);

    /** 조회 전에 읽은 결제 사실이다. 조회 중 다른 실행이 저장한 사실까지 보장하는 잠금은 아니다. */
    record LookupRequest(ExpectedPayment expected, PaymentAttempt.Outcome outcome, PaymentAttempt.Rejection rejection) {
        public LookupRequest {
            Objects.requireNonNull(expected);
            Objects.requireNonNull(outcome);
            Objects.requireNonNull(rejection);
        }

        public static LookupRequest from(PaymentAttempt attempt) {
            return new LookupRequest(new ExpectedPayment(attempt.getProviderOrderId(), attempt.getAmount(),
                attempt.getTransactionKey()), attempt.getOutcome(), attempt.getRejection());
        }
    }
}
