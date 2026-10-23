package com.loopers.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * 현재 시각의 기준. 주문서 만료 · 결제 시각 판단에 쓰며, 테스트에서는 고정한 Clock 으로 바꿔 끼움 (설계 2.3).
 * 시간대는 기존 ZonedDateTime.now() 와 같은 시스템 시간대를 따름.
 * DB(DATETIME(6))와 같은 마이크로초 단위로 잘라, 저장 직후 값과 다시 읽은 값이 같게 함.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return new MicrosecondClock(Clock.systemDefaultZone());
    }

    /** Clock.tick 은 1ms 미만 단위에서 millis() 가 0 으로 나눠 실패하므로 직접 자름 */
    static final class MicrosecondClock extends Clock {
        private final Clock base;

        MicrosecondClock(Clock base) {
            this.base = base;
        }

        @Override
        public ZoneId getZone() {
            return base.getZone();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MicrosecondClock(base.withZone(zone));
        }

        @Override
        public Instant instant() {
            return base.instant().truncatedTo(ChronoUnit.MICROS);
        }
    }
}
