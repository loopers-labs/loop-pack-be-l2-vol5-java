package com.loopers.infrastructure.payment;

import java.time.Duration;

/**
 * PG 조회의 retry·회로 설정. 값은 application.yml의 commerce.payment.pg 아래에 두며 동작 학습용 임시값이다.
 * attemptEstimate는 다음 재시도를 시작할지 판단할 때 쓰는 조회 한 번의 추정 시간이다.
 */
public record LookupResilienceSettings(
    Duration budget,
    Duration attemptEstimate,
    int maxAttempts,
    Duration firstWait,
    double waitMultiplier,
    int windowSize,
    int minimumCalls,
    float failureRateThreshold,
    Duration openWait,
    int halfOpenCalls
) {
    /** 마지막 재시도 앞의 쉬는 시간. 남은 예산을 보수적으로 판단하는 데 쓴다. 재시도가 없으면 0이다. */
    Duration longestWait() {
        if (maxAttempts < 2) {
            return Duration.ZERO;
        }
        return Duration.ofMillis(Math.round(firstWait.toMillis() * Math.pow(waitMultiplier, maxAttempts - 2)));
    }
}
