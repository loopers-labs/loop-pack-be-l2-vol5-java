package com.loopers.batch.job.ranking;

import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 주간 랭킹 job 의 식별 파라미터. 세 값 모두 JobInstance 를 구분한다 (9주차 D-186).
 * snapshotId 는 기간 · revision 으로 만들며, 진입점을 거치지 않는 실행에서 어긋날 수 있어 생성할 때 검증한다
 */
public record WeeklyRankingParameters(LocalDate periodStart, int revision, String snapshotId) {

    public static final String PERIOD_START = "periodStart";
    public static final String REVISION = "revision";
    public static final String SNAPSHOT_ID = "snapshotId";

    private static final DateTimeFormatter SNAPSHOT_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    public WeeklyRankingParameters {
        if (periodStart == null || periodStart.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new IllegalArgumentException("periodStart 는 월요일이어야 함. periodStart=" + periodStart);
        }
        if (revision < 1) {
            throw new IllegalArgumentException("revision 은 1 이상. revision=" + revision);
        }
        String expected = snapshotIdOf(periodStart, revision);
        if (!expected.equals(snapshotId)) {
            throw new IllegalArgumentException("snapshotId 가 기간 · revision 과 다름. expected=" + expected + " actual=" + snapshotId);
        }
    }

    public static WeeklyRankingParameters of(LocalDate periodStart, int revision) {
        return new WeeklyRankingParameters(periodStart, revision, snapshotIdOf(periodStart, revision));
    }

    public static WeeklyRankingParameters from(JobParameters params) {
        Long revision = params.getLong(REVISION);
        if (revision == null) {
            throw new IllegalArgumentException("revision 이 없음");
        }
        return new WeeklyRankingParameters(params.getLocalDate(PERIOD_START), Math.toIntExact(revision), params.getString(SNAPSHOT_ID));
    }

    public static String snapshotIdOf(LocalDate periodStart, int revision) {
        return "weekly-" + periodStart.format(SNAPSHOT_DATE) + "-r" + revision;
    }

    public JobParameters toJobParameters() {
        return new JobParametersBuilder()
            .addLocalDate(PERIOD_START, periodStart, true)
            .addLong(REVISION, (long) revision, true)
            .addString(SNAPSHOT_ID, snapshotId, true)
            .toJobParameters();
    }

    /** 기간 끝(제외). UTC 기준 다음 월요일 */
    public LocalDate periodEnd() {
        return periodStart.plusDays(7);
    }
}
