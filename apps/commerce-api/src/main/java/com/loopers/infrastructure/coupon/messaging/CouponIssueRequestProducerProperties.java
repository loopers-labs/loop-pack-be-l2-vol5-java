package com.loopers.infrastructure.coupon.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 사용자가 응답을 기다리는 접수 경로라 relay 보다 짧게 둔 잠정값 (8주차 D-132, D-134) */
@ConfigurationProperties("coupon-issue.producer")
public record CouponIssueRequestProducerProperties(
    Duration requestTimeout,
    Duration deliveryTimeout,
    /** delivery timeout 보다 길게 둬 대부분 future 가 먼저 끝나게 함. 그래도 넘기면 수신 여부를 모름 */
    Duration sendTimeout,
    Duration maxBlock
) {}
