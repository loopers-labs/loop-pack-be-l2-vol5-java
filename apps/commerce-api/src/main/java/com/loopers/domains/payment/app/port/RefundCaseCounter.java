package com.loopers.domains.payment.app.port;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

public interface RefundCaseCounter {
    // 돈의 상태별 수, 사람 담당 중 수, 환불 요청함 중 가장 오래된 요청 시각(없으면 비어 있음)을 한 번에 읽는다.
    Snapshot snapshot();

    record Snapshot(Map<String, Long> countsByMoneyStatus, long personOpen, Optional<Instant> oldestRefundRequestedAt) { }
}
