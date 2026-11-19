package com.loopers.batch.job.ranking;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * 랭킹 테이블에 쓰는 현재 시각. DB 시각 컬럼은 UTC 날짜·시각이고 마이크로초까지 저장하므로,
 * 주입한 시계의 순간을 마이크로초로 잘라 UTC LocalDateTime으로 돌려준다. JDBC에는 Hibernate의 UTC 설정이 적용되지 않는다.
 */
@Component
public class RankingTime {

    private final Clock clock;

    public RankingTime(Clock clock) {
        this.clock = clock;
    }

    public LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }
}
