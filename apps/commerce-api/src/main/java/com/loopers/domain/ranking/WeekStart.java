package com.loopers.domain.ranking;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

public record WeekStart(LocalDate value) {

    public WeekStart {
        if (value == null || value.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new IllegalArgumentException("periodStart 는 월요일이어야 합니다: " + value);
        }
    }

    public static WeekStart of(String isoDate) {
        try {
            return new WeekStart(LocalDate.parse(isoDate));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("periodStart 는 yyyy-MM-dd 여야 합니다: " + isoDate, e);
        }
    }

    public LocalDate end() {
        return value.plusWeeks(1);
    }
}
