package com.loopers.domains.coupon.app.port;

import java.util.List;

// 재개 확인 모드(D140)가 쓰는 DB 조회. 바꾸는 것은 없다. 조회할 수 없으면(권한 포함) AdmissionUnavailableException.
public interface CouponAdmissionInspection {
    // coupon_campaign·issued_coupon 잠금을 쥔 열린 트랜잭션 수.
    int openCouponLockTransactions();

    // issued_count와 실제 발급 쿠폰 수가 다른 캠페인 ID.
    List<Long> issuedCountMismatches();
}
