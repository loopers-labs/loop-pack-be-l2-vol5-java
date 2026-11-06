package com.loopers.config;

import com.loopers.outbox.domain.OrderOutboxBackoff;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.util.concurrent.ThreadLocalRandom;

/**
 * relay가 쓰는 시계·backoff·작업 잠금 (docs/week7/plan.md D-0 lease 책임 나눔, D-3, 4단계 겹침 방지).
 * <p>
 * ShedLock은 outbox 행이 아니라 <b>작업 이름 하나</b>("order-outbox-relay")에 거는 잠금이다. shedlock 테이블의 그 행을 얻은 실행만
 * relay 한 회차를 돌린다 — 앱이 여러 대여도 relay는 하나다. 잠금을 쥔 채 죽으면 lockAtMostFor(5분) 뒤 풀린다.
 * 시각 비교는 DB 시각으로 한다(usingDbTime) — 앱 서버들의 시계가 달라도 같은 기준으로 만료를 본다.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "5m")
public class OrderOutboxRelayConfig {

    /** 현재 시각은 앱이 정한다(DB NOW()가 아니다). 단일 relay라 서버 간 시계 차이가 없다 */
    @Bean
    Clock relayClock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    OrderOutboxBackoff orderOutboxBackoff() {
        return new OrderOutboxBackoff(() -> ThreadLocalRandom.current().nextDouble());
    }

    @Bean
    LockProvider lockProvider(JdbcTemplate jdbcTemplate) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
            .withJdbcTemplate(jdbcTemplate)
            .usingDbTime()
            .build());
    }
}
