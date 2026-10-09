package com.loopers.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

/** 낙관적 잠금 충돌 시 유스케이스를 새 트랜잭션으로 다시 실행하는 ~Retrier 를 켬 (3주차 설계 4.4) */
@EnableRetry
@Configuration
public class RetryConfig {
}
