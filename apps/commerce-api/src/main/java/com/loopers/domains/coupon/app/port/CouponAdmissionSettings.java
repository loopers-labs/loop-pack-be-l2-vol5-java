package com.loopers.domains.coupon.app.port;

import java.time.Instant;

public interface CouponAdmissionSettings {
    // 설정 행이 없으면 닫힘으로 본다. 읽을 수 없으면 AdmissionUnavailableException.
    AdmissionSettings current();

    AdmissionStatus status();

    // 닫기는 epoch를 바꾸지 않는다(D138).
    void close(String reason, Instant at);

    // 열기만 epoch를 1 올린다(D138). 실제로 바꿨으면 true다.
    boolean open(Instant at);
}
