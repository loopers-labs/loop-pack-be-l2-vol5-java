package com.loopers.batch.job.ranking;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * weeklyRanking job의 식별 parameter(periodStart, snapshotId)를 해석한 값.
 * snapshotId는 {@code weekly-{yyyyMMdd}-r{revision}} 형식이고 날짜가 periodStart와 같아야 한다.
 * 같은 값을 다시 넘기면 같은 JobInstance로 재시작되고, revision을 올리면 명시적인 재계산이다 (docs/week9/design.md D3).
 * 기간은 UTC {@code [periodStart 00:00, periodStart+7일 00:00)}이다.
 */
public record WeeklySnapshotKey(LocalDate periodStart, String snapshotId, int revision) {

    private static final Pattern SNAPSHOT_ID = Pattern.compile("weekly-(\\d{8})-r([1-9]\\d{0,5})");
    private static final DateTimeFormatter COMPACT = DateTimeFormatter.BASIC_ISO_DATE;

    public static WeeklySnapshotKey of(String periodStart, String snapshotId) {
        if (periodStart == null || snapshotId == null) {
            throw new IllegalArgumentException("periodStart와 snapshotId는 필수입니다. periodStart=" + periodStart + ", snapshotId=" + snapshotId);
        }
        LocalDate start;
        try {
            start = LocalDate.parse(periodStart);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("periodStart는 yyyy-MM-dd 형식이어야 합니다. periodStart=" + periodStart, e);
        }
        if (start.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new IllegalArgumentException("periodStart는 월요일이어야 합니다. periodStart=" + periodStart);
        }
        Matcher matcher = SNAPSHOT_ID.matcher(snapshotId);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("snapshotId는 weekly-yyyyMMdd-rN 형식이어야 합니다. snapshotId=" + snapshotId);
        }
        if (!matcher.group(1).equals(start.format(COMPACT))) {
            throw new IllegalArgumentException("snapshotId의 날짜가 periodStart와 다릅니다. periodStart=" + periodStart + ", snapshotId=" + snapshotId);
        }
        return new WeeklySnapshotKey(start, snapshotId, Integer.parseInt(matcher.group(2)));
    }

    public static String snapshotIdOf(LocalDate periodStart, int revision) {
        return "weekly-" + periodStart.format(COMPACT) + "-r" + revision;
    }

    public LocalDate periodEnd() {
        return periodStart.plusDays(7);
    }

    /** 구간 시작(포함). UTC 기준 LocalDateTime이다. */
    public LocalDateTime startInclusive() {
        return periodStart.atStartOfDay();
    }

    /** 구간 끝(제외). UTC 기준 LocalDateTime이다. */
    public LocalDateTime endExclusive() {
        return periodEnd().atStartOfDay();
    }
}
