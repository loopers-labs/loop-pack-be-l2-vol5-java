package com.loopers.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// 결제 회복 작업(PaymentRecoveryScheduler)을 주기적으로 실행한다 (docs/week5/design.md D-040).
@EnableScheduling
@Configuration
public class SchedulingConfig {
}
