package com.loopers.domain.coupon;

/**
 * DB 발급 TX에 동시에 들어가는 실행 수를 캠페인마다 묶는다(분산락은 1개, 세마포어는 N개).
 * 자리가 날 때까지 호출 스레드가 기다린다. 비교 실험용이며 기본은 제한하지 않는다.
 */
public interface CouponIssueLimiter {
    /**
     * 자리를 얻을 때까지 기다린다.
     *
     * @return 자리를 얻으면 true, 정해진 시간 안에 못 얻으면 false
     */
    boolean acquire(Long campaignId);

    /** acquire로 얻은 자리를 돌려준다. acquire를 부른 스레드에서 부른다. */
    void release(Long campaignId);
}
