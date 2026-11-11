package com.loopers.infrastructure.coupon;

import com.loopers.application.coupon.CouponIssueAdmissionState;
import com.loopers.application.coupon.CouponIssuePermits;
import com.loopers.application.coupon.CouponIssueProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Order(100)
@RequiredArgsConstructor
public class CouponIssueAdmissionStartupRunner implements ApplicationRunner {

    private final CouponIssueProperties properties;
    private final CouponIssuePermits permits;
    private final CouponIssueAdmissionState admission;

    @Override
    public void run(ApplicationArguments args) {
        admission.close();
        if (!properties.admission().openOnStartup()) {
            return;
        }

        try {
            if (!permits.isEmpty()) {
                log.warn("쿠폰 permit Set이 비어 있지 않아 신규 발급 수용을 닫아 둡니다.");
                return;
            }
            admission.open();
            log.info("쿠폰 발급 수용을 시작했습니다. capacity={}", properties.capacity());
        } catch (RuntimeException exception) {
            admission.close();
            log.warn("쿠폰 permit 상태를 확인하지 못해 신규 발급 수용을 닫아 둡니다.", exception);
        }
    }
}
