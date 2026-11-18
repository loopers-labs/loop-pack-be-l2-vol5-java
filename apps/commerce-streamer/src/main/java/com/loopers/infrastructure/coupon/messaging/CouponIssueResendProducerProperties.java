package com.loopers.infrastructure.coupon.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 확인 대기 재전송 producer 의 시간 값. commerce-api 접수 producer 와 같은 잠정값 (8주차 D-134) */
@ConfigurationProperties("coupon-issue.resend-producer")
public record CouponIssueResendProducerProperties(
    Duration requestTimeout,
    Duration deliveryTimeout,
    Duration sendTimeout,
    Duration maxBlock
) {}
