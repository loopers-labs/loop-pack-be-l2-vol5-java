package com.loopers.application.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

// 주기 조회 설정. idleThreshold는 마지막 상태 변경 뒤 기다릴 시간, lookupWindow는 생성 후 자동 조회를 계속할 기간이다.
@ConfigurationProperties("payment.periodic-check")
public record PaymentPeriodicCheckProperties(boolean enabled, Duration interval, Duration idleThreshold,
                                             int batchSize, Duration lookupWindow) {}
