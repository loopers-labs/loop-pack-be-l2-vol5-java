package com.loopers.domain.ranking;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * UTC 월요일 00:00부터 다음 월요일 00:00 전까지의 한 주. 끝(periodEnd)은 다음 월요일이고 기간에 포함하지 않는다.
 */
public record RankingPeriod(LocalDate start) {

    public RankingPeriod {
        if (start == null || start.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new CoreException(ErrorType.BAD_REQUEST, "기간 시작은 월요일이어야 합니다.");
        }
    }

    public static RankingPeriod of(LocalDate start) {
        return new RankingPeriod(start);
    }

    public LocalDate end() {
        return start.plusDays(7);
    }
}
