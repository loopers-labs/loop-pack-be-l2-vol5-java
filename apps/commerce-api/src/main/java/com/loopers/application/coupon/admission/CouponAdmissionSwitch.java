package com.loopers.application.coupon.admission;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 이 인스턴스의 신규 쿠폰 수용 스위치. 기동하면 닫혀 있고 관리자가 확인한 뒤에만 연다.
 * Redis 가 데이터를 유지한 채 재시작해도 수용이 저절로 다시 열리지 않게 Redis 밖에 둔다.
 */
@Component
public class CouponAdmissionSwitch {

    private final AtomicBoolean open = new AtomicBoolean(false);

    public boolean isOpen() {
        return open.get();
    }

    public void open() {
        open.set(true);
    }

    public void close() {
        open.set(false);
    }
}
