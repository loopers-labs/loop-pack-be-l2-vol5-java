package com.loopers.domains.coupon.app.port;

import java.time.Instant;

public interface CouponAdmissionSettings {
    // 설정 행이 없으면 닫힘으로 본다. 읽을 수 없으면 AdmissionUnavailableException.
    AdmissionSettings current();

    void close(String reason, Instant at);

    void open(Instant at);
}
