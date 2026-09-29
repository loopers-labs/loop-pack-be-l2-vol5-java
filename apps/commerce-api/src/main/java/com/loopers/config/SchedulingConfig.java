package com.loopers.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 포인트 만료 스케줄러(ADR-10)를 켠다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {}
