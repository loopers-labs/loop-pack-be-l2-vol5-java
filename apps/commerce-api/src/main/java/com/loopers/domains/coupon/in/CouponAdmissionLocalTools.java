package com.loopers.domains.coupon.in;

import com.loopers.domains.coupon.app.port.CouponAdmissionSettings;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// 로컬 전용 쿠폰 수용 재개 도구(D132, 2단계는 열기만). 공개 API를 두지 않고 앱 시작 인자로만 받는다: --coupon.admission.open
// 자동 재개는 없다. 사람이 원인을 확인한 뒤 연다. 원인 확인 모드는 3단계에서 붙인다.
@Slf4j
@Component
@Profile("local")
public class CouponAdmissionLocalTools implements ApplicationRunner {
    private static final String OPEN_OPTION = "coupon.admission.open";

    private final CouponAdmissionSettings couponAdmissionSettings;
    private final Clock clock;

    public CouponAdmissionLocalTools(CouponAdmissionSettings couponAdmissionSettings, Clock clock) {
        this.couponAdmissionSettings = couponAdmissionSettings;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(OPEN_OPTION)) {
            return;
        }
        couponAdmissionSettings.open(clock.instant());
        log.info("쿠폰 수용을 열었다");
    }
}
