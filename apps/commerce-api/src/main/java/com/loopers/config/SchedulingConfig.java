package com.loopers.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// 결제 주기 조회처럼 설정으로 켠 스케줄러의 @Scheduled 메서드를 실행한다.
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
