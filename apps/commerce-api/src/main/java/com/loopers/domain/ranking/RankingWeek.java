package com.loopers.domain.ranking;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** 주간 랭킹 기간. UTC 월요일부터 다음 월요일 전까지이며 periodEnd 는 포함하지 않는다. */
public record RankingWeek(LocalDate periodStart) {

    public static RankingWeek from(String periodStart) {
        LocalDate date;
        try {
            date = LocalDate.parse(periodStart);
        } catch (DateTimeParseException e) {
            throw new CoreException(ErrorType.INVALID_INPUT, "periodStart 는 yyyy-MM-dd 형식이어야 합니다.");
        }
        if (date.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new CoreException(ErrorType.INVALID_INPUT, "periodStart 는 월요일이어야 합니다.");
        }
        return new RankingWeek(date);
    }

    public LocalDate periodEnd() {
        return periodStart.plusWeeks(1);
    }
}
