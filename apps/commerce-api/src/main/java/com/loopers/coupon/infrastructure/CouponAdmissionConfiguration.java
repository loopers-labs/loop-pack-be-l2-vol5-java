package com.loopers.coupon.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CouponAdmissionProperties.class)
public class CouponAdmissionConfiguration {
}
