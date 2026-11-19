package com.loopers.domain.ranking;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 주간 랭킹 Job 의 식별 파라미터 규칙 (설계 D1·D12).
 * snapshotId 는 {@code weekly-YYYYMMDD-rN} 이며 날짜는 UTC 월요일, N 은 1 이상의 revision 이다.
 * 기간은 [periodStart 00:00Z, periodStart+7일 00:00Z) 이고 JDBC 바인딩용 UTC LocalDateTime 으로 제공한다.
 */
public record WeeklySnapshotId(String value, LocalDate periodStart, int revision) {

    private static final Pattern FORMAT = Pattern.compile("^weekly-(\\d{8})-r(\\d+)$");

    /** snapshotId 만 파싱한다. 형식 오류·revision 0 이하는 IllegalArgumentException. */
    public static WeeklySnapshotId parse(String snapshotId) {
        if (snapshotId == null) {
            throw new IllegalArgumentException("snapshotId 는 필수입니다.");
        }
        Matcher matcher = FORMAT.matcher(snapshotId);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("snapshotId 형식은 weekly-YYYYMMDD-rN 이어야 합니다: " + snapshotId);
        }
        LocalDate date;
        try {
            date = LocalDate.parse(matcher.group(1), DateTimeFormatter.BASIC_ISO_DATE);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("snapshotId 의 날짜가 올바르지 않습니다: " + snapshotId, e);
        }
        int revision;
        try {
            revision = Integer.parseInt(matcher.group(2));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("snapshotId 의 revision 이 올바르지 않습니다: " + snapshotId, e);
        }
        if (revision < 1) {
            throw new IllegalArgumentException("snapshotId 의 revision 은 1 이상이어야 합니다: " + snapshotId);
        }
        return new WeeklySnapshotId(snapshotId, date, revision);
    }

    /**
     * snapshotId 와 periodStart(YYYY-MM-DD) 를 함께 검증한다 (설계 D1).
     * 두 날짜가 다르거나 periodStart 가 월요일이 아니면 IllegalArgumentException (메시지에 두 값).
     */
    public static WeeklySnapshotId of(String snapshotId, String periodStart) {
        WeeklySnapshotId parsed = parse(snapshotId);
        if (periodStart == null) {
            throw new IllegalArgumentException("periodStart 는 필수입니다. snapshotId=" + snapshotId);
        }
        LocalDate start;
        try {
            start = LocalDate.parse(periodStart);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                "periodStart 형식은 YYYY-MM-DD 이어야 합니다. periodStart=" + periodStart + ", snapshotId=" + snapshotId, e);
        }
        if (!start.equals(parsed.periodStart())) {
            throw new IllegalArgumentException(
                "snapshotId 의 날짜와 periodStart 가 다릅니다. periodStart=" + periodStart + ", snapshotId=" + snapshotId);
        }
        if (start.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new IllegalArgumentException(
                "periodStart 는 UTC 월요일이어야 합니다. periodStart=" + periodStart + ", snapshotId=" + snapshotId);
        }
        return parsed;
    }

    /** 기간 시작 (포함), UTC 벽시계. */
    public LocalDateTime periodStartUtc() {
        return periodStart.atTime(LocalTime.MIDNIGHT);
    }

    /** 기간 끝 (제외), UTC 벽시계. */
    public LocalDateTime periodEndUtc() {
        return periodStart.plusDays(7).atTime(LocalTime.MIDNIGHT);
    }
}
