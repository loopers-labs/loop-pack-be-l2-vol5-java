package com.loopers.domain.ranking;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * BC-06 랭킹의 주간 기간 (설계 10-1). UTC 월요일 00:00 부터 다음 월요일 00:00 전까지. 구매는 최초 확정 시각으로 한 주에 속한다.
 *
 * @param start UTC 월요일 (포함)
 */
public record RankingPeriod(LocalDate start) {

    public RankingPeriod {
        if (start == null || start.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new CoreException(ErrorType.INVALID_RANKING_PERIOD, "periodStart 는 월요일이어야 합니다. [periodStart = " + start + "]");
        }
    }

    /** yyyy-MM-dd 만 받는다. 없거나 형식이 틀리면 INVALID_RANKING_PERIOD. */
    public static RankingPeriod parse(String periodStart) {
        if (periodStart == null || periodStart.isBlank()) {
            throw new CoreException(ErrorType.INVALID_RANKING_PERIOD, "periodStart 가 없습니다.");
        }
        try {
            return new RankingPeriod(LocalDate.parse(periodStart));
        } catch (DateTimeParseException e) {
            throw new CoreException(ErrorType.INVALID_RANKING_PERIOD, "periodStart 형식은 yyyy-MM-dd 입니다. [periodStart = " + periodStart + "]");
        }
    }

    /** 다음 월요일 (제외). */
    public LocalDate end() {
        return start.plusWeeks(1);
    }
}
