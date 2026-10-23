package com.loopers.infrastructure.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

/**
 * 테스트 PG 연결 설정 (docs/week5/pg-contract.md 5·6절). 주소는 설정으로 고정하고 고객 입력으로 바꿀 수 없다.
 *
 * @param providerUserId       PG로 보내는 합성 제공자 계정({@code X-USER-ID}). 고객 식별과 관계가 없다
 * @param createReadTimeout    create 응답 대기. timeout은 UNKNOWN을 만드는 비싼 결과라 조회보다 길게 둔다
 * @param lookupReadTimeout    조회 응답 대기. 다시 물어도 안전하므로 짧게 끊는다
 * @param lookupRetry          조회 재시도. create에는 적용하지 않는다
 * @param lookupCircuitBreaker 조회 회로 차단기. create에는 적용하지 않는다
 */
@ConfigurationProperties("payment.pg")
public record PgProperties(
    String baseUrl,
    String callbackUrl,
    String providerUserId,
    Duration connectTimeout,
    Duration createReadTimeout,
    Duration lookupReadTimeout,
    LookupRetry lookupRetry,
    LookupCircuitBreaker lookupCircuitBreaker
) {
    public PgProperties {
        requireText(baseUrl, "payment.pg.base-url");
        requireText(callbackUrl, "payment.pg.callback-url");
        Objects.requireNonNull(providerUserId, "payment.pg.provider-user-id가 없다");
        Objects.requireNonNull(connectTimeout, "payment.pg.connect-timeout이 없다");
        Objects.requireNonNull(createReadTimeout, "payment.pg.create-read-timeout이 없다");
        Objects.requireNonNull(lookupReadTimeout, "payment.pg.lookup-read-timeout이 없다");
        Objects.requireNonNull(lookupRetry, "payment.pg.lookup-retry가 없다");
        Objects.requireNonNull(lookupCircuitBreaker, "payment.pg.lookup-circuit-breaker가 없다");
    }

    /**
     * @param maxAttempts 첫 호출을 포함한 최대 시도 수 (Resilience4j {@code maxAttempts})
     * @param initialWait 첫 재시도 전 대기. 이후 대기는 {@code multiplier}배씩 늘어난다
     */
    public record LookupRetry(int maxAttempts, Duration initialWait, double multiplier) {
        public LookupRetry {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("payment.pg.lookup-retry.max-attempts는 1 이상이어야 한다");
            }
            Objects.requireNonNull(initialWait, "payment.pg.lookup-retry.initial-wait가 없다");
        }
    }

    /**
     * @param slidingWindowSize            호출 수 기준 창 크기
     * @param minimumNumberOfCalls         실패율을 계산하기 시작하는 최소 표본
     * @param failureRateThreshold         이 실패율(%) 이상이면 연다
     * @param waitDurationInOpenState      연 뒤 탐색 호출을 허용하기까지의 대기
     * @param permittedCallsInHalfOpenState 반쯤 연 상태에서 허용하는 탐색 호출 수
     */
    public record LookupCircuitBreaker(
        int slidingWindowSize,
        int minimumNumberOfCalls,
        float failureRateThreshold,
        Duration waitDurationInOpenState,
        int permittedCallsInHalfOpenState
    ) {
        public LookupCircuitBreaker {
            Objects.requireNonNull(waitDurationInOpenState, "payment.pg.lookup-circuit-breaker.wait-duration-in-open-state가 없다");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "가 없다");
        }
    }
}
