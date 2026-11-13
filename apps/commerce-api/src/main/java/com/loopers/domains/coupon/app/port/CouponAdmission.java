package com.loopers.domains.coupon.app.port;

public interface CouponAdmission {
    // epoch 키에 자리가 있으면 token으로 하나 차지하고 true, 없으면 false다. 확인할 수 없으면 AdmissionUnavailableException.
    boolean acquire(long epoch, String token, int capacity);

    // 같은 epoch 키에서 자기 token만 돌려준다. 이미 돌려줬거나 모르는 token이면 아무것도 바뀌지 않는다.
    void release(long epoch, String token);

    // epoch 키에 남은 token 수(확인 모드 참고용). 확인할 수 없으면 AdmissionUnavailableException.
    long holders(long epoch);
}
