package com.loopers.application.promotion;

import java.util.Optional;

/**
 * 쿠폰 발급 TX 의 동시 실행 허가 (DR-61). 프로모션마다 따로 센다.
 * 허가는 lease 가 지나면 반납하지 않아도 회수된다.
 */
public interface CouponIssueSemaphore {

    /**
     * 허가 하나를 얻는다. 없으면 waitTime 까지 기다린다.
     * 얻으면 반납할 때 쓸 토큰, 끝내 없거나 저장소 오류·인터럽트면 empty.
     */
    Optional<String> tryAcquire(Long promotionId);

    /** 토큰의 허가를 반납한다. 이미 만료·회수됐으면 아무것도 하지 않는다. 오류는 던지지 않는다. */
    void release(Long promotionId, String token);
}
