package com.loopers.fixture.pg;

import java.time.Instant;

/** 실제 전송 시도 1건의 관측. 콜백 URL·요청 원문은 노출하지 않는다. completedAt 은 진행 중이면 null 이다. */
public record CallbackRecord(
    long sequence,
    String transactionKey,
    String providerOrderId,
    PaymentStatus status,
    Instant startedAt,
    Instant completedAt,
    Integer httpStatus,
    CallbackFailure failure
) {
}
