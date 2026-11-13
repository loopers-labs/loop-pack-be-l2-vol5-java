package com.loopers.application.coupon;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Objects;

/**
 * 쿠폰 신규 발급의 동시 실행 수용 설정(docs/week8 설계서 6·13절). HTTP 입력으로 받지 않는 서버 설정이다.
 *
 * @param capacity   지금 동시에 진행할 수 있는 쿠폰 신규 발급 실행의 최대 개수(C). DB 연결 풀 크기 이하로 둔다
 * @param autoResume Redis가 복구되고 안전 조건을 채우면 자동으로 세마포어 경로로 돌아올지(재개 단계에서 사용)
 * @param instanceId 이 인스턴스의 고정 ID. token을 {@code instanceId:UUID}로 만들어, 재시작 뒤 자기 token만 정리할 수 있게 한다(단일 인스턴스 전제)
 */
@ConfigurationProperties("coupon.admission")
public record CouponAdmissionProperties(int capacity, boolean autoResume, String instanceId) {
    public CouponAdmissionProperties {
        if (capacity < 1) {
            throw new IllegalArgumentException("coupon.admission.capacity는 1 이상이어야 한다");
        }
        Objects.requireNonNull(instanceId, "coupon.admission.instance-id가 없다");
        // token 접두어({instanceId}:)로 Redis를 검색하므로 glob 특수문자와 ':'를 허용하지 않는다
        if (!instanceId.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("coupon.admission.instance-id는 영문·숫자·'_'·'.'·'-'만 쓸 수 있다");
        }
    }
}
