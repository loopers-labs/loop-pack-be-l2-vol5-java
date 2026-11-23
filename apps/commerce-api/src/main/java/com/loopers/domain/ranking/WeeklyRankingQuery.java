package com.loopers.domain.ranking;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/** 주간 랭킹 조회 입력(랭킹 설계 7.7). 날짜 → limit 순서로 검증하고 값을 보정하지 않는다. */
public record WeeklyRankingQuery(LocalDate periodStart, LocalDate periodEnd, int limit) {

    private static final Pattern DATE_SHAPE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 100;

    public static WeeklyRankingQuery of(String periodStartRaw, String limitRaw) {
        LocalDate periodStart = parsePeriodStart(periodStartRaw);
        return new WeeklyRankingQuery(periodStart, periodStart.plusDays(7), parseLimit(limitRaw));
    }

    private static LocalDate parsePeriodStart(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw new CoreException(ErrorType.INVALID_REQUEST, "periodStart가 필요합니다.");
        }
        CoreException invalidDate = new CoreException(ErrorType.INVALID_REQUEST, "periodStart는 YYYY-MM-DD 형식의 유효한 날짜여야 합니다.");
        if (!DATE_SHAPE.matcher(raw).matches()) {
            throw invalidDate;
        }
        LocalDate date;
        try {
            date = LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            throw invalidDate;
        }
        if (date.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new CoreException(ErrorType.INVALID_REQUEST, "periodStart는 UTC 기준 월요일이어야 합니다.");
        }
        return date;
    }

    private static int parseLimit(String raw) {
        if (raw == null) {
            return DEFAULT_LIMIT;
        }
        int limit;
        try {
            limit = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new CoreException(ErrorType.BAD_REQUEST, "limit은 정수여야 합니다.");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new CoreException(ErrorType.INVALID_REQUEST, "limit은 1~100이어야 합니다.");
        }
        return limit;
    }
}
