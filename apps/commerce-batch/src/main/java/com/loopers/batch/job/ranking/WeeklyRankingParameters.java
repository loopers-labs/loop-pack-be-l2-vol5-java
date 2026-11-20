package com.loopers.batch.job.ranking;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Job 식별 parameter(D152). revision은 3단계 런처가 정해 snapshotId(weekly-YYYYMMDD-rN)에 담아 넘긴다.
// revision은 앞자리 0을 받지 않는다(r1과 r01이 같은 revision을 가리키지 않게, w9s2-1).
// snapshotId의 날짜가 periodStart와 다르거나 periodStart가 월요일이 아니면 받지 않는다(이름·기간 어긋남 방지).
public record WeeklyRankingParameters(LocalDate periodStart, String snapshotId, int revision) {
    public static final String PERIOD_START = "periodStart";
    public static final String SNAPSHOT_ID = "snapshotId";
    public static final String RANKING_SIZE = "rankingSize";

    private static final Pattern SNAPSHOT_ID_PATTERN = Pattern.compile("weekly-(\\d{8})-r([1-9]\\d*)");
    private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    public static WeeklyRankingParameters parse(String periodStart, String snapshotId) {
        if (periodStart == null || snapshotId == null) {
            throw new IllegalArgumentException("periodStart와 snapshotId가 필요합니다.");
        }
        LocalDate start;
        LocalDate named;
        Matcher matcher = SNAPSHOT_ID_PATTERN.matcher(snapshotId);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("snapshotId 형식은 weekly-YYYYMMDD-rN입니다. snapshotId=" + snapshotId);
        }
        try {
            start = LocalDate.parse(periodStart);
            named = LocalDate.parse(matcher.group(1), COMPACT_DATE);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("날짜를 읽을 수 없습니다. periodStart=" + periodStart + ", snapshotId=" + snapshotId, e);
        }
        int revision;
        try {
            revision = Integer.parseInt(matcher.group(2));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("revision을 읽을 수 없습니다. snapshotId=" + snapshotId, e);
        }
        if (revision < 1) {
            throw new IllegalArgumentException("revision은 1 이상입니다. snapshotId=" + snapshotId);
        }
        if (start.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new IllegalArgumentException("periodStart는 월요일이어야 합니다. periodStart=" + periodStart);
        }
        if (!named.equals(start)) {
            throw new IllegalArgumentException("snapshotId의 날짜가 periodStart와 다릅니다. periodStart=" + periodStart
                + ", snapshotId=" + snapshotId);
        }
        return new WeeklyRankingParameters(start, snapshotId, revision);
    }

    // 확장 job처럼 snapshotId만 받는 곳에서 쓴다. 날짜는 이름에서 읽는다.
    public static WeeklyRankingParameters fromSnapshotId(String snapshotId) {
        Matcher matcher = SNAPSHOT_ID_PATTERN.matcher(snapshotId == null ? "" : snapshotId);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("snapshotId 형식은 weekly-YYYYMMDD-rN입니다. snapshotId=" + snapshotId);
        }
        String named = matcher.group(1);
        return parse(named.substring(0, 4) + "-" + named.substring(4, 6) + "-" + named.substring(6), snapshotId);
    }

    public static String snapshotIdOf(LocalDate periodStart, int revision) {
        return "weekly-" + periodStart.format(COMPACT_DATE) + "-r" + revision;
    }

    // 완성 랭킹 저장 개수 K는 1 이상이다(D156). 없으면 설정값을 쓴다.
    public static void requireRankingSize(Long rankingSize) {
        if (rankingSize != null && (rankingSize < 1 || rankingSize > Integer.MAX_VALUE)) {
            throw new IllegalArgumentException("rankingSize는 1 이상입니다. rankingSize=" + rankingSize);
        }
    }

    // 구간 끝(제외)은 다음 월요일이다.
    public LocalDate periodEnd() {
        return periodStart.plusDays(7);
    }
}
