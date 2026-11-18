package com.loopers.domain.ranking.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.DayOfWeek;
import java.time.ZoneOffset;

public record RankingPeriod(LocalDate start) {
    public RankingPeriod {
        if (start == null || start.getDayOfWeek() != DayOfWeek.MONDAY || start.getYear() < 1000
            || start.getYear() > 9999 || start.plusDays(7).getYear() > 9999) {
            throw new IllegalArgumentException("주간 기간은 MySQL 날짜 범위 내 UTC 월요일부터 7일이어야 합니다.");
        }
    }

    public LocalDate end() {
        return start.plusDays(7);
    }

    public Instant startInclusive() {
        return start.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    public Instant endExclusive() {
        return end().atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    public boolean contains(Instant instant) {
        return instant != null && !instant.isBefore(startInclusive()) && instant.isBefore(endExclusive());
    }
}
