package com.loopers.domain.ranking;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public record WeeklyRankingKey(LocalDate periodStart, int revision) {
    public WeeklyRankingKey {
        if (periodStart == null || periodStart.getDayOfWeek() != DayOfWeek.MONDAY || revision <= 0) {
            throw new IllegalArgumentException("Weekly ranking requires a Monday and a positive revision");
        }
    }

    public String snapshotId() {
        return "weekly-" + periodStart.format(DateTimeFormatter.BASIC_ISO_DATE) + "-r" + revision;
    }

    public LocalDate periodEnd() {
        return periodStart.plusWeeks(1);
    }

    public Instant startInclusive() {
        return periodStart.atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    public Instant endExclusive() {
        return periodEnd().atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    public boolean contains(Instant confirmedAt) {
        if (confirmedAt == null) {
            throw new IllegalArgumentException("Purchase confirmation instant must not be null");
        }
        return !confirmedAt.isBefore(startInclusive()) && confirmedAt.isBefore(endExclusive());
    }
}
