package com.loopers.domain.payment;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentAttemptRepository {
    PaymentAttempt save(PaymentAttempt attempt);

    Optional<PaymentAttempt> findById(Long id);

    Optional<PaymentAttempt> findByOrderId(Long orderId);

    Optional<PaymentAttempt> findByUserIdAndAttemptKey(Long userId, String attemptKey);

    /**
     * TX B ⑥ 결과 반영 문지기. 미종결(REQUESTED·PENDING·UNKNOWN)일 때만 SUCCESS·FAILED 로 종결한다.
     * WHERE status IN ('REQUESTED', 'PENDING', 'UNKNOWN').
     *
     * @return 갱신된 행 수. 0 이면 이미 종결된 시도라 아무 효과도 없어야 한다.
     */
    int settleIfOpen(Long attemptId, PaymentAttempt.Status status, PaymentAttempt.FailureReason failureReason, String transactionKey);

    /**
     * 생성 수락 또는 조회로 거래를 확인했을 때 PENDING 과 transactionKey 를 기록한다.
     * WHERE status IN ('REQUESTED', 'UNKNOWN'). 종결된 시도는 되돌리지 않는다.
     *
     * @return 갱신된 행 수
     */
    int markPendingIfNotSettled(Long attemptId, String transactionKey);

    /**
     * timeout·응답 유실을 UNKNOWN 으로 기록한다. WHERE status = 'REQUESTED'. 이미 PENDING 이면 되돌리지 않는다.
     *
     * @return 갱신된 행 수
     */
    int markUnknownIfRequested(Long attemptId);

    /**
     * callback 본문의 providerOrderId 로 시도를 찾는다 (설계 7-5 결정 ⑲ 순서 1).
     */
    Optional<PaymentAttempt> findByProviderOrderId(String providerOrderId);

    /**
     * 스케줄러 조회 대상 (설계 5-3 결정 ⑫). 미종결이고, 수동 확인 대상이 아니며,
     * 마지막 변경(updatedAt)이 updatedBefore 이전이고, nextLookupAt 이 없거나 now 이전인 시도.
     */
    List<PaymentAttempt> findAllOpenDueForLookup(ZonedDateTime updatedBefore, ZonedDateTime now);

    /**
     * 충돌 기록 (설계 7-2). 상태 컬럼은 건드리지 않으므로 ⑥ 문지기를 거치지 않는다.
     *
     * @return 갱신된 행 수
     */
    int recordConflict(Long attemptId, PaymentAttempt.ConflictReason reason, ZonedDateTime at);

    /**
     * 스케줄러가 PG 에 물어본 뒤 lookup_count = lookup_count + 1, next_lookup_at, manual_check_required 를 한 UPDATE 로 기록한다.
     *
     * @return 갱신된 행 수
     */
    int recordLookup(Long attemptId, ZonedDateTime nextLookupAt, boolean manualCheckRequired);
}