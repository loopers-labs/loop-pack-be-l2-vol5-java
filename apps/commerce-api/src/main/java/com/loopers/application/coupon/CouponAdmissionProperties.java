package com.loopers.application.coupon;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

/**
 * 쿠폰 신규 발급의 동시 실행 수용 설정(설계서 6·8·13절). HTTP 입력으로 받지 않는 서버 설정이다.
 *
 * @param capacity   지금 동시에 진행할 수 있는 쿠폰 신규 발급 실행의 최대 개수(C). DB 연결 풀 크기 이하로 둔다
 * @param autoResume Redis가 복구되고 안전 조건을 채우면 자동으로 세마포어 경로로 돌아올지. false면 조건이 충족돼도 사람이 확인해야 한다
 * @param instanceId 이 인스턴스의 고정 ID. token을 {@code instanceId:UUID}로 만들어, 재시작 뒤 자기 token만 정리할 수 있게 한다(단일 인스턴스 전제)
 * @param breaker    Redis 호출 CircuitBreaker 설정. 생략하면 기본값(설계서 13절)을 쓴다
 */
@ConfigurationProperties("coupon.admission")
public record CouponAdmissionProperties(int capacity, boolean autoResume, String instanceId, Breaker breaker) {

    public CouponAdmissionProperties {
        if (capacity < 1) {
            throw new IllegalArgumentException("coupon.admission.capacity는 1 이상이어야 한다");
        }
        Objects.requireNonNull(instanceId, "coupon.admission.instance-id가 없다");
        // token 접두어({instanceId}:)로 Redis를 검색하므로 glob 특수문자와 ':'를 허용하지 않는다
        if (!instanceId.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("coupon.admission.instance-id는 영문·숫자·'_'·'.'·'-'만 쓸 수 있다");
        }
        if (breaker == null) {
            breaker = Breaker.defaults();
        }
    }

    /**
     * @param slidingWindowSize         최근 호출 수 기준 창 크기
     * @param minimumNumberOfCalls      이 수만큼 호출되기 전에는 실패율로 열지 않는다
     * @param failureRateThreshold      실패율(%)이 이 값 이상이면 연다
     * @param waitDurationInOpenState   열린 뒤 다시 시험하기까지의 최소 대기(재개 probe의 최소 대기이기도 하다)
     * @param permittedCallsInHalfOpen  시험(half-open) 상태에서 허용하는 호출 수
     */
    public record Breaker(
        int slidingWindowSize,
        int minimumNumberOfCalls,
        int failureRateThreshold,
        Duration waitDurationInOpenState,
        int permittedCallsInHalfOpen
    ) {
        public Breaker {
            if (slidingWindowSize < 1 || minimumNumberOfCalls < 1 || permittedCallsInHalfOpen < 1) {
                throw new IllegalArgumentException("coupon.admission.breaker의 창·최소 호출·시험 호출 수는 1 이상이어야 한다");
            }
            if (failureRateThreshold < 1 || failureRateThreshold > 100) {
                throw new IllegalArgumentException("coupon.admission.breaker.failure-rate-threshold는 1~100이어야 한다");
            }
            Objects.requireNonNull(waitDurationInOpenState, "coupon.admission.breaker.wait-duration-in-open-state가 없다");
        }

        static Breaker defaults() {
            return new Breaker(10, 5, 50, Duration.ofSeconds(5), 3);
        }
    }
}
