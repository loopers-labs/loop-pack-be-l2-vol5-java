package com.loopers.application.ordering.result;

import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentRecoveryTask;

// 실행 비교값이 없으면 완료 또는 선점 패자다. 최신 응답만 읽고 PG를 호출하지 않는다.
public record PrepareOrderReconciliationResult(long ownerUserId, PaymentAttempt attempt,
                                               PaymentRecoveryTask.Snapshot execution) {}
