package com.loopers.domains.coupon.app.port;

public interface CouponAdmission {
    // 자리가 있으면 token으로 하나 차지하고 true, 없으면 false다. 확인할 수 없으면 AdmissionUnavailableException.
    boolean acquire(String token, int capacity);

    // 자기 token만 돌려준다. 이미 돌려줬거나 모르는 token이면 아무것도 바뀌지 않는다.
    void release(String token);
}
