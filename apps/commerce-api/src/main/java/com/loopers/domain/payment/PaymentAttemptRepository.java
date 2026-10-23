package com.loopers.domain.payment;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentAttemptRepository {

    PaymentAttempt save(PaymentAttempt attempt);

    Optional<PaymentAttempt> findById(Long attemptId);

    Optional<PaymentAttempt> findByPaymentAttemptKey(String paymentAttemptKey);

    Optional<PaymentAttempt> findByOrderId(Long orderId);

    List<PaymentAttempt> findAllByOrderIds(Collection<Long> orderIds);

    Optional<PaymentAttempt> findByProviderOrderId(String providerOrderId);

    // 아래 갱신은 결과 반영 설계의 허용 상태·거래 키 조건을 DB에서 검사하고 갱신한 행 수를 반환한다.
    int markPending(Long attemptId, String transactionKey, ZonedDateTime changedAt);

    int markUnknown(Long attemptId, ZonedDateTime changedAt);

    int terminate(Long attemptId, PaymentAttemptStatus terminalStatus, String transactionKey, ZonedDateTime changedAt);

    int rejectBeforeCreate(Long attemptId, ZonedDateTime changedAt);

    // 충돌 기록과 확인 시각은 상태 변경이 아니므로 updated_at을 바꾸지 않는다.
    int recordConflict(Long attemptId, PaymentConflictReason reason, ZonedDateTime detectedAt);

    int touchLastChecked(Long attemptId, ZonedDateTime checkedAt);

    List<PaymentAttempt> findDueForCheck(ZonedDateTime changedBefore, ZonedDateTime createdAfter, int limit);
}
