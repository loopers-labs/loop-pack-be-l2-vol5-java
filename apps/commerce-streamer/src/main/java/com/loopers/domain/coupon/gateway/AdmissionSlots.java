package com.loopers.domain.coupon.gateway;

import java.util.Set;

/** 세마포어 실험의 처리 자리(Redis Set 의 실행 token). 실제 발급 경로에는 쓰지 않는다 (8주차 D-114, D-157) */
public interface AdmissionSlots {

    /** @return 수용됐으면 true, 자리가 없으면 false. 결과를 모르면 예외 */
    boolean acquire(long campaignId, int capacity, String token);

    /** 자기 token 만 지운다. 두 번 지우거나 모르는 token 이어도 다른 자리는 남는다 */
    void release(long campaignId, String token);

    Set<String> tokens(long campaignId);
}
