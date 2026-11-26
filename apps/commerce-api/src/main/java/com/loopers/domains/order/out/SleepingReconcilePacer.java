package com.loopers.domains.order.out;

import com.loopers.domains.order.app.port.ReconcilePacer;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class SleepingReconcilePacer implements ReconcilePacer {
    @Override
    public void pause(Duration interval) {
        if (interval.isZero() || interval.isNegative()) {
            return;
        }
        try {
            Thread.sleep(interval);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("일괄 조회 확정 간격 대기 중 중단되었습니다.", e);
        }
    }
}
