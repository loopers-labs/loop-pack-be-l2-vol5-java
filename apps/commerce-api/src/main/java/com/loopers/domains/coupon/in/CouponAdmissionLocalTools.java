package com.loopers.domains.coupon.in;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
public class CouponAdmissionLocalTools implements ApplicationRunner {
    @Override
    public void run(ApplicationArguments args) {
    }
}
