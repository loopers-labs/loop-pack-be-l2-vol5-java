package com.loopers.batch.job.reconcile;

import java.time.Instant;

// 대사가 어디까지 확인했는지. 같은 확정 시각 동률을 가르려고 주문 ID까지 두 값으로 둔다.
public record ReconcileCheckpoint(Instant confirmedAt, long orderId) {
    @Override
    public String toString() {
        return "(" + confirmedAt + ", " + orderId + ")";
    }
}
