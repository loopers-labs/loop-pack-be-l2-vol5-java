package com.loopers.domain.ranking;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * 조회 API 의 주간 규칙 (설계 D12·D14): periodStart 는 YYYY-MM-DD 의 UTC 월요일, 기간은 [periodStart, periodStart+7일).
 * commerce-batch 의 WeeklySnapshotId 와 같은 규칙을 작게 다시 쓴 것이다 — 두 앱이 공유 모듈을 갖지 않아서다 (D14 한계).
 */
public record WeeklyPeriod(LocalDate periodStart) {

    public static WeeklyPeriod parse(String periodStart) {
        LocalDate start;
        try {
            start = LocalDate.parse(periodStart);
        } catch (DateTimeParseException e) {
            throw new CoreException(ErrorType.BAD_REQUEST,
                "periodStart 는 YYYY-MM-DD 형식의 UTC 월요일이어야 합니다. periodStart=" + periodStart);
        }
        if (start.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new CoreException(ErrorType.BAD_REQUEST,
                "periodStart 는 UTC 월요일이어야 합니다. periodStart=" + periodStart);
        }
        return new WeeklyPeriod(start);
    }

    /** 기간 끝 (제외). */
    public LocalDate periodEnd() {
        return periodStart.plusDays(7);
    }
}
