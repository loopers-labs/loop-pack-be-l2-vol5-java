package com.loopers.outbox.domain;

import java.time.Duration;
import java.util.function.DoubleSupplier;

/**
 * send 실패 뒤 다음 시도까지 기다릴 시간 (docs/week7/plan.md D-3). 지수 backoff + 상한 + full jitter.
 * <p>
 * n번째 실패 뒤 대기 = random[0, 1) × min(상한, 기본 × 배수^(n-1)). 기본 10초 · 배수 3 · 상한 5분 → 1차 0~10초, 2차 0~30초.
 * 난수는 주입받는다 — 테스트에서 고정하고, 도메인 객체({@link OrderOutbox})는 난수를 모른다.
 */
public class OrderOutboxBackoff {

    public static final Duration BASE = Duration.ofSeconds(10);
    public static final int MULTIPLIER = 3;
    public static final Duration CAP = Duration.ofMinutes(5);

    /** [0, 1) 사이의 값을 돌려준다 */
    private final DoubleSupplier random;

    public OrderOutboxBackoff(DoubleSupplier random) {
        this.random = random;
    }

    /**
     * @param failureNumber 이번이 몇 번째 연속 실패인가 (1부터)
     */
    public Duration delayAfter(int failureNumber) {
        return Duration.ZERO;
    }
}
