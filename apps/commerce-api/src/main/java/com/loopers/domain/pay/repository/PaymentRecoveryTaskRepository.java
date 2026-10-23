package com.loopers.domain.pay.repository;

import com.loopers.domain.pay.model.PaymentRecoveryTask;
import java.time.Instant;
import java.util.Optional;

/** 일반 save는 제공하지 않는다. 실행 중 변경은 DB의 현재 소유권/회차 조건을 검증한다. */
public interface PaymentRecoveryTaskRepository {
    void insert(PaymentRecoveryTask task);
    Optional<PaymentRecoveryTask> findByKey(String attemptKey);
    boolean claim(String key, String executionId, Instant now);
    boolean consumeRound(String key, String executionId, int expectedUsed, Instant now);
    boolean startManualLookup(String key, String executionId, int expectedUsed, Instant now);
    boolean handover(String key, PaymentRecoveryTask.State expectedState, String expectedExecutionId,
                     int expectedUsed, String nextExecutionId, Instant now);
    boolean recordFailure(String key, String executionId, int expectedUsed, PaymentRecoveryTask.Reason reason, Instant now);
    boolean requireManual(String key, String executionId, int expectedUsed, PaymentRecoveryTask.Reason reason, Instant now);

    /** 주문·재고의 공통 업무 반영 TX 안에서만 호출하며 callback도 같은 경계를 사용한다. */
    boolean markApplied(String key, Instant now);

    /** 실패한 업무 시도 당시의 상태·실행·회차가 그대로일 때만 수동 표시한다. */
    boolean markApplicationFailed(PaymentRecoveryTask.Snapshot expected, String markerExecutionId, Instant now);
}
