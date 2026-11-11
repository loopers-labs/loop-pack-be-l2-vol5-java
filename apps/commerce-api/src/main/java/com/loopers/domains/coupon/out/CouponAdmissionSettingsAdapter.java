package com.loopers.domains.coupon.out;

import com.loopers.domains.coupon.app.port.AdmissionSettings;
import com.loopers.domains.coupon.app.port.CouponAdmissionSettings;
import java.time.Instant;
import org.springframework.stereotype.Repository;

@Repository
public class CouponAdmissionSettingsAdapter implements CouponAdmissionSettings {
    private final CouponAdmissionSettingsJpaRepository couponAdmissionSettingsJpaRepository;

    public CouponAdmissionSettingsAdapter(CouponAdmissionSettingsJpaRepository couponAdmissionSettingsJpaRepository) {
        this.couponAdmissionSettingsJpaRepository = couponAdmissionSettingsJpaRepository;
    }

    @Override
    public AdmissionSettings current() {
        return new AdmissionSettings(Integer.MAX_VALUE, true);
    }

    @Override
    public void close(String reason, Instant at) {
    }

    @Override
    public void open(Instant at) {
    }
}
