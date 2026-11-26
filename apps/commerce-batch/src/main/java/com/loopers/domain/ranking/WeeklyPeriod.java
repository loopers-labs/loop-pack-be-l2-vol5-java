package com.loopers.domain.ranking;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** UTC 월요일 00:00 부터 다음 월요일 00:00 전까지의 주간 업무 기간. */
public record WeeklyPeriod(LocalDate periodStart) {

    private static final DateTimeFormatter SNAPSHOT_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    public WeeklyPeriod {
        if (periodStart == null || periodStart.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new IllegalArgumentException("periodStart 는 월요일이어야 합니다: " + periodStart);
        }
    }

    public Instant startInclusive() {
        return periodStart.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    public Instant endExclusive() {
        return periodStart.plusWeeks(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    public String snapshotId(int revision) {
        return "weekly-" + SNAPSHOT_DATE.format(periodStart) + "-r" + revision;
    }
}
