package com.loopers.testpg.domain;

// 거래 결과와 별도로 제어하는 PG 조회 응답의 지연 설정이다. 0ms는 정상 상태다.
public record LookupScenario(long delayMillis, int failureCount, int failureStatus) {
    public LookupScenario(long delayMillis) {
        this(delayMillis, 0, 503);
    }

    public LookupScenario {
        if (delayMillis < 0 || delayMillis > 60_000) {
            throw new IllegalArgumentException("조회 지연은 0~60,000ms여야 합니다.");
        }
        if (failureCount < 0 || failureCount > 10_000) {
            throw new IllegalArgumentException("조회 실패 횟수는 0~10,000회여야 합니다.");
        }
        if (failureStatus != 502 && failureStatus != 503 && failureStatus != 504) {
            throw new IllegalArgumentException("조회 실패 상태는 502, 503, 504만 설정합니다.");
        }
    }

    public LookupScenario afterResponse() {
        return failureCount == 0 ? this : new LookupScenario(delayMillis, failureCount - 1, failureStatus);
    }
}
