package com.loopers.ranking.domain;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

public record WeeklyRankingPeriod(LocalDate start, LocalDate end) {

    public static WeeklyRankingPeriod parse(String value) {
        try {
            LocalDate start = LocalDate.parse(value);
            if (start.getDayOfWeek() != DayOfWeek.MONDAY) {
                throw new CoreException(ErrorCode.INVALID_REQUEST);
            }
            return new WeeklyRankingPeriod(start, start.plusWeeks(1));
        } catch (DateTimeParseException | NullPointerException exception) {
            throw new CoreException(ErrorCode.INVALID_REQUEST);
        }
    }
}
