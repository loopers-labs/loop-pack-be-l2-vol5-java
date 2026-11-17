package com.loopers.coupon.domain;

import java.util.random.RandomGenerator;

/**
 * 다음 순번 조회까지 기다릴 시간 (D-4-5). 서버가 정한다(HTTP Retry-After와 같은 생각).
 * 예상 대기(앞 인원 ÷ 초당 입장 수)의 절반, 0.5–5초, ±20% jitter — 같은 초에 등록한 사람들이 같은 간격으로 한꺼번에 몰리지 않게.
 */
public final class PollInterval {

    public static final long MIN_MS = 500;
    public static final long MAX_MS = 5_000;
    static final double JITTER = 0.2;

    private PollInterval() {}

    public static long of(long ahead, int admitPerSecond, RandomGenerator random) {
        long expectedWaitMs = ahead * 1_000L / Math.max(1, admitPerSecond);
        long base = Math.clamp(expectedWaitMs / 2, MIN_MS, MAX_MS);
        double factor = 1 + (random.nextDouble() * 2 - 1) * JITTER;
        return Math.max(MIN_MS, Math.round(base * factor));
    }
}
