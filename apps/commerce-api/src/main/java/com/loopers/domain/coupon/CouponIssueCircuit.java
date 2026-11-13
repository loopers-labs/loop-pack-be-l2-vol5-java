package com.loopers.domain.coupon;

/**
 * DB 발급 구간의 서킷브레이커 (8주차 설계 §7-4, D15, INV-W8-13).
 * DB 는 모든 캠페인이 공유하므로 서킷은 전체에 하나다. Redis 장애는 gate 가, DB 장애는 이 서킷이 맡는다. DB 장애로는 gate 를 닫지 않는다.
 *
 * 허가(tryAcquire)는 세마포어 자리를 잡기 전에 받는다. 서킷이 열려 있으면 DB 도 세마포어도 호출하지 않고 503 으로 끝내기 위해서다.
 * 허가를 받았지만 DB 호출 없이 끝나면(세마포어 거절 등) release 로 돌려준다. 그래야 반열림의 시험 호출 수가 어긋나지 않는다.
 */
public interface CouponIssueCircuit {

    enum State {
        CLOSED, OPEN, HALF_OPEN
    }

    State state();

    /**
     * @return 호출을 허가하면 true. 열려 있거나 반열림 시험 호출 수를 다 썼으면 false
     */
    boolean tryAcquire();

    /** 허가를 받았지만 DB 를 호출하지 않고 끝날 때 허가를 돌려준다. */
    void release();

    void recordSuccess();

    /**
     * 실패 후보를 기록한다. DataAccessException 계열만 실패로 세고, CoreException(품절·종료·없음 등 업무 결과)은 세지 않는다.
     */
    void recordFailure(Throwable cause);
}
