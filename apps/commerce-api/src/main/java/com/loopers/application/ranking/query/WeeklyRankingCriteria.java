package com.loopers.application.ranking.query;

import java.time.DayOfWeek;
import java.time.LocalDate;

public record WeeklyRankingCriteria(LocalDate periodStart, int limit) {
    public WeeklyRankingCriteria {
        if (periodStart == null || periodStart.getDayOfWeek() != DayOfWeek.MONDAY || periodStart.getYear() < 1000
            || periodStart.getYear() > 9999 || periodStart.plusDays(7).getYear() > 9999) {
            throw new IllegalArgumentException("periodStart는 MySQL 날짜 범위 안에서 UTC 월요일로 시작하는 주를 지정해야 합니다.");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit은 1 이상 100 이하의 정수여야 합니다.");
        }
    }

    public LocalDate periodEnd() {
        return periodStart.plusDays(7);
    }
}
