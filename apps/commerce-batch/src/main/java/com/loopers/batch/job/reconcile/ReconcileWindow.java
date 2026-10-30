package com.loopers.batch.job.reconcile;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

// 읽는 구간 [시작 위치 다음, 지금 − 유예). 시작 위치는 (확정 시각, 주문 ID)로 그 위치는 포함하지 않는다.
// 겹침이 있으면 워터마크 시각 − 겹침부터 그 시각을 포함해 읽는다(주문 ID 0 다음 = 그 시각 전부).
public record ReconcileWindow(Instant fromAt, long fromOrderId, Instant toAt) {
    public static ReconcileWindow of(Optional<ReconcileCheckpoint> checkpoint, Duration overlap, Duration grace, Instant now) {
        Instant toAt = now.minus(grace);
        if (checkpoint.isEmpty()) {
            return new ReconcileWindow(Instant.EPOCH, 0, toAt);
        }
        ReconcileCheckpoint watermark = checkpoint.get();
        if (overlap.isZero()) {
            return new ReconcileWindow(watermark.confirmedAt(), watermark.orderId(), toAt);
        }
        return new ReconcileWindow(watermark.confirmedAt().minus(overlap), 0, toAt);
    }

    public ReconcileCheckpoint end() {
        return new ReconcileCheckpoint(toAt, Long.MAX_VALUE);
    }

    @Override
    public String toString() {
        String from = fromOrderId == 0 ? fromAt.toString() : fromAt + "(orderId>" + fromOrderId + ")";
        return "[" + from + ", " + toAt + ")";
    }
}
