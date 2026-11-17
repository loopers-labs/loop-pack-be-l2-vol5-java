package com.loopers.domain.ranking;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/** 한 주간 계산본의 식별값. periodStart 는 UTC 업무 주의 월요일이다. */
public record WeeklyRankingRequest(LocalDate periodStart, LocalDate periodEnd, long revision, String snapshotId) {

    private static final Pattern DATE_SHAPE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final DateTimeFormatter ID_DATE = DateTimeFormatter.ofPattern("uuuuMMdd");

    public static WeeklyRankingRequest of(String periodStart, Long revision, String snapshotId) {
        LocalDate start = parseMonday(periodStart);
        if (revision == null || revision <= 0) {
            throw invalid("revision 은 양수여야 합니다.");
        }
        String expectedId = canonicalId(start, revision);
        if (!expectedId.equals(snapshotId)) {
            throw invalid("snapshotId 가 기간·revision 과 맞지 않습니다.");
        }
        return new WeeklyRankingRequest(start, start.plusDays(7), revision, expectedId);
    }

    public static String canonicalId(LocalDate periodStart, long revision) {
        return "weekly-" + periodStart.format(ID_DATE) + "-r" + revision;
    }

    private static LocalDate parseMonday(String raw) {
        if (raw == null || !DATE_SHAPE.matcher(raw).matches()) {
            throw invalid("periodStart 는 YYYY-MM-DD 형식이어야 합니다.");
        }
        LocalDate date;
        try {
            date = LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            throw invalid("periodStart 가 올바른 날짜가 아닙니다.");
        }
        if (date.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw invalid("periodStart 는 월요일이어야 합니다.");
        }
        return date;
    }

    private static RankingInputException invalid(String message) {
        return new RankingInputException(RankingInputException.Reason.INVALID_REQUEST, message);
    }
}
