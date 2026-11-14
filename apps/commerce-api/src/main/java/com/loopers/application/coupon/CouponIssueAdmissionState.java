package com.loopers.application.coupon;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class CouponIssueAdmissionState {

    private final AtomicBoolean open = new AtomicBoolean(false);

    public boolean isOpen() {
        return open.get();
    }

    public void requireOpen() {
        if (!isOpen()) {
            throw new CoreException(ErrorType.SERVICE_UNAVAILABLE, "쿠폰 발급 수용이 닫혀 있습니다.");
        }
    }

    public void open() {
        open.set(true);
    }

    public void close() {
        open.set(false);
    }
}
