package com.loopers.domains.order.app;

import java.time.Duration;

// 실패 뒤 다음 시도까지의 간격(D116 [임시]): 1초에서 두 배씩 늘려 60초에서 멈춘다. jitter는 두지 않는다.
// 갱신 뒤의 attempt_count로 계산한다(1번째 실패 → 1초).
public final class OutboxRetryPolicy {
    private static final Duration INITIAL = Duration.ofSeconds(1);
    private static final Duration MAX = Duration.ofSeconds(60);
    // 2^6초(64초)부터는 상한을 넘으므로 그 위로는 거듭제곱을 계산하지 않아 넘침이 없다.
    private static final int DOUBLINGS_BEFORE_MAX = 6;

    private OutboxRetryPolicy() {
    }

    public static Duration backoff(int attemptCount) {
        int doublings = Math.max(0, attemptCount - 1);
        if (doublings >= DOUBLINGS_BEFORE_MAX) {
            return MAX;
        }
        Duration delay = INITIAL.multipliedBy(1L << doublings);
        return delay.compareTo(MAX) > 0 ? MAX : delay;
    }
}
