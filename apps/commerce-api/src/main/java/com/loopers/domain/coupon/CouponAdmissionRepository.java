package com.loopers.domain.coupon;

/**
 * 쿠폰 신규 발급 수용 자리를 실행별 token 으로 점유·반환한다. 모든 캠페인이 같은 자리 묶음을 공유한다.
 * Redis 오류·timeout·비정상 응답은 결과값이 아니라 예외로 전달한다. 예외는 명령이 적용되지 않았다는 뜻이 아니다.
 */
public interface CouponAdmissionRepository {

    /** 같은 token 이 이미 있으면 점유를 늘리지 않고 ACQUIRED 다. capacity 는 신뢰한 서버 설정 값이다. */
    Acquisition tryAcquire(String token, int capacity);

    /**
     * 지정한 token 하나만 제거하고 제거 수(0 또는 1)를 돌려준다. 다른 점유는 바꾸지 않는다.
     * 반복 호출·없는 token 의 0 은 명령으로서 정상이며, 정상 실행의 첫 반환 0 을 이상으로 볼지는 호출자가 판단한다.
     */
    long release(String token);

    enum Acquisition {
        ACQUIRED,
        FULL
    }
}
