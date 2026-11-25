package com.loopers.domains.order.app;

import java.time.Duration;

public record ReconcileBatchPolicy(int maxOrders, Duration interval, int consecutiveFailureLimit) {
    public ReconcileBatchPolicy {
        if (maxOrders < 1 || interval == null || interval.isNegative() || consecutiveFailureLimit < 1) {
            throw new IllegalArgumentException("일괄 조회 확정 설정은 최대 건수·연속 실패 한도 1 이상, 간격 0 이상이어야 합니다.");
        }
    }
}
