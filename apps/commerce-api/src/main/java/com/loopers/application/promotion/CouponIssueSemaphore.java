package com.loopers.application.promotion;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 쿠폰 발급 TX 의 동시 실행 허가 (DR-63). 프로모션마다 따로 센다.
 * 허가는 lease 가 지나면 반납하지 않아도 회수된다.
 */
public interface CouponIssueSemaphore {

    /**
     * 허가 하나를 얻는다. 없으면 waitTime 까지 기다린다. 호출 스레드는 기다리지 않고 바로 돌아온다.
     * future 는 얻으면 반납할 때 쓸 토큰, 끝내 없거나 저장소 오류면 empty 로 끝난다. 예외로 끝나지 않는다.
     */
    CompletableFuture<Optional<String>> tryAcquire(Long promotionId);

    /** 토큰의 허가를 반납한다. 기다리지 않는다. 이미 만료·회수됐으면 아무것도 하지 않는다. 오류는 던지지 않는다. */
    void release(Long promotionId, String token);
}
