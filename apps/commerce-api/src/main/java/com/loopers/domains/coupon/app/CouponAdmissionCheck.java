package com.loopers.domains.coupon.app;

import com.loopers.domains.coupon.app.port.CouponAdmission;
import com.loopers.domains.coupon.app.port.CouponAdmissionInspection;
import com.loopers.domains.coupon.app.port.CouponAdmissionSettings;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CouponAdmissionCheck {
    public record Reopened(long previousEpoch, long newEpoch) { }

    private final CouponAdmissionSettings couponAdmissionSettings;
    private final CouponAdmissionInspection couponAdmissionInspection;
    private final CouponAdmission couponAdmission;
    private final Clock clock;
    private final Duration maxExecution;

    public CouponAdmissionCheck(CouponAdmissionSettings couponAdmissionSettings, CouponAdmissionInspection couponAdmissionInspection,
        CouponAdmission couponAdmission, Clock clock, @Value("${commerce.coupon.admission.max-execution}") Duration maxExecution) {
        this.couponAdmissionSettings = couponAdmissionSettings;
        this.couponAdmissionInspection = couponAdmissionInspection;
        this.couponAdmission = couponAdmission;
        this.clock = clock;
        this.maxExecution = maxExecution;
    }

    public AdmissionCheckReport check() {
        AdmissionCheckReport.Criterion ok = new AdmissionCheckReport.Criterion(true, "");
        return new AdmissionCheckReport(false, null, null, null, 0, ok, ok, ok, null, true);
    }

    public Reopened open() {
        long next = couponAdmissionSettings.open(clock.instant());
        return new Reopened(next, next);
    }
}
