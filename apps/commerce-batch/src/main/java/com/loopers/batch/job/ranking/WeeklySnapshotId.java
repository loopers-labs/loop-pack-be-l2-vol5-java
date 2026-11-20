package com.loopers.batch.job.ranking;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 주간 랭킹 입력 snapshot의 이름표. 형식은 {@code weekly-{yyyyMMdd}-r{revision}}이고 날짜는 UTC 월요일이다.
 * 같은 기간의 재계산은 revision으로 구분한다.
 */
public record WeeklySnapshotId(LocalDate periodStart, int revision) {
    private static final Pattern FORMAT = Pattern.compile("weekly-(\\d{8})-r([1-9]\\d{0,5})");
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;

    public WeeklySnapshotId {
        if (periodStart == null || periodStart.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new IllegalArgumentException("주간 기간의 시작은 월요일이어야 합니다: " + periodStart);
        }
        if (revision < 1) {
            throw new IllegalArgumentException("revision은 1 이상이어야 합니다: " + revision);
        }
    }

    public static WeeklySnapshotId parse(String value) {
        Matcher matcher = value == null ? null : FORMAT.matcher(value);
        if (matcher == null || !matcher.matches()) {
            throw new IllegalArgumentException("snapshotId 형식은 weekly-yyyyMMdd-rN 입니다: " + value);
        }
        try {
            return new WeeklySnapshotId(LocalDate.parse(matcher.group(1), DATE), Integer.parseInt(matcher.group(2)));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("snapshotId의 날짜가 올바르지 않습니다: " + value, e);
        }
    }

    public String value() {
        return "weekly-" + periodStart.format(DATE) + "-r" + revision;
    }

    /** 기간 시작(포함). UTC 시각이다. */
    public LocalDateTime startInclusive() {
        return periodStart.atStartOfDay();
    }

    /** 기간 끝(제외). 다음 월요일 00:00 UTC다. */
    public LocalDateTime endExclusive() {
        return periodStart.plusWeeks(1).atStartOfDay();
    }
}
