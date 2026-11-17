package com.loopers.application.coupon.port;

import java.time.Instant;

public interface CouponDatabaseClock {
    /** 활성 업무 TX의 쓰기 연결에서 새 SQL로 조회한 DB 시각. */
    Instant now();
}
