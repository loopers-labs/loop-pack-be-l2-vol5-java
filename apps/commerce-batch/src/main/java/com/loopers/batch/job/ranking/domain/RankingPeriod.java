package com.loopers.batch.job.ranking.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Objects;

public record RankingPeriod(LocalDate start) {

    public RankingPeriod {
        Objects.requireNonNull(start, "periodStart");
        if (start.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new IllegalArgumentException("periodStart must be a Monday: " + start);
        }
    }

    public static RankingPeriod parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("periodStart is required");
        }
        try {
            return new RankingPeriod(LocalDate.parse(value));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("periodStart must be yyyy-MM-dd: " + value, e);
        }
    }

    public LocalDate end() {
        return start.plusWeeks(1);
    }

    public LocalDateTime startInclusiveUtc() {
        return start.atStartOfDay();
    }

    public LocalDateTime endExclusiveUtc() {
        return end().atStartOfDay();
    }
}
