package com.loopers.infrastructure.query.ordering;

import com.loopers.application.pay.query.PaymentAttemptView;
import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentRecoveryTask;
import java.time.Instant;

// 카드·테스트 조건은 선택하지 않는다. LEFT JOIN의 빈 행은 결제 시도 없음으로 변환한다.
public record PaymentAttemptRow(String key, String dispatchState, String outcome, String transactionKey,
                                String rejection, String conflict, String recoveryState, Integer usedRounds,
                                Instant lastCheckedAt, String lastReason) {
    PaymentAttemptView toView() {
        if (key == null) { return null; }
        return new PaymentAttemptView(key, PaymentAttempt.DispatchState.valueOf(dispatchState),
            PaymentAttempt.Outcome.valueOf(outcome), transactionKey, PaymentAttempt.Rejection.valueOf(rejection),
            PaymentAttempt.Conflict.valueOf(conflict),
            recoveryState == null ? null : PaymentRecoveryTask.State.valueOf(recoveryState),
            usedRounds == null ? 0 : usedRounds, lastCheckedAt,
            lastReason == null ? null : PaymentRecoveryTask.Reason.valueOf(lastReason));
    }
}
