package com.loopers.job.reconcile;

import com.loopers.batch.job.reconcile.ReconcileCheckpoint;
import com.loopers.batch.job.reconcile.ReconcileWindow;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReconcileWindowTest {
    private static final Instant NOW = Instant.parse("2026-10-29T01:00:00Z");
    private static final Duration GRACE = Duration.ofMinutes(5);
    private static final ReconcileCheckpoint WATERMARK = new ReconcileCheckpoint(Instant.parse("2026-10-29T00:42:00Z"), 2);

    @Test
    @DisplayName("워터마크가 없으면(첫 실행) 가장 이른 시각부터 지금 − 유예까지 읽는다")
    void firstRunStartsFromEarliest() {
        assertThat(ReconcileWindow.of(Optional.empty(), Duration.ofMinutes(1), GRACE, NOW))
            .isEqualTo(new ReconcileWindow(Instant.EPOCH, 0, Instant.parse("2026-10-29T00:55:00Z")));
    }

    @Test
    @DisplayName("겹침이 0이면 워터마크 위치(확정 시각, 주문 ID) 다음부터 읽어 같은 시각의 뒤 주문 ID만 본다")
    void zeroOverlapStartsAfterWatermarkPosition() {
        assertThat(ReconcileWindow.of(Optional.of(WATERMARK), Duration.ZERO, GRACE, NOW))
            .isEqualTo(new ReconcileWindow(Instant.parse("2026-10-29T00:42:00Z"), 2, Instant.parse("2026-10-29T00:55:00Z")));
    }

    @Test
    @DisplayName("겹침이 있으면 워터마크 시각 − 겹침부터 그 시각의 모든 주문을 포함해 읽고, 성공한 실행의 끝 위치는 구간 끝·최대 주문 ID다")
    void overlapStartsBeforeWatermarkInclusive() {
        ReconcileWindow window = ReconcileWindow.of(Optional.of(WATERMARK), Duration.ofMinutes(1), GRACE, NOW);

        assertThat(window).isEqualTo(new ReconcileWindow(Instant.parse("2026-10-29T00:41:00Z"), 0,
            Instant.parse("2026-10-29T00:55:00Z")));
        assertThat(window.end()).isEqualTo(new ReconcileCheckpoint(Instant.parse("2026-10-29T00:55:00Z"), Long.MAX_VALUE));
    }
}
