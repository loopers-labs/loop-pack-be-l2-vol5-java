package com.loopers.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Outbox relay가 "지금"을 얻는 시계(docs/week7/decisions.md D-11). 행을 고르는 조건(next_attempt_at &lt;= 지금)과
 * 임대·백오프·24시간 계산이 모두 이 시계를 쓴다 — DB NOW()를 쓰면 테스트가 시간을 넘길 수 없고, 앱 시각과 DB 시각이
 * 섞인다. 운영은 UTC 시스템 시계이고, 테스트는 실제 현재 시각에서 시작해 앞으로만 넘기는 시계를 넣는다(D-16).
 *
 * <p>결제 반영(PaymentResultApplier)은 이 시계로 옮기지 않는다 — D-16 범위 밖이다.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
