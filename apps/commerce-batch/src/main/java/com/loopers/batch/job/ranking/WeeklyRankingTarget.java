package com.loopers.batch.job.ranking;

import org.springframework.batch.core.JobParameter;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.Instant;

/**
 * weeklyRanking job 의 대상 (설계 10-3, DR-65). 식별 parameter 는 periodStart·revision 두 개이고 둘 다 문자열이다.
 * snapshotId·기간 끝은 이 둘로 만든다. 같은 snapshot 을 다른 표기로 가리키지 않도록 정규 표기만 받는다 ("2026-08-17", "1").
 *
 * @param periodStart UTC 월요일. 기간은 [periodStart 00:00Z, periodStart + 7일 00:00Z)
 * @param revision    기간별 1 부터 1 씩 (DR-66)
 */
public record WeeklyRankingTarget(LocalDate periodStart, long revision) {
    public static final String PERIOD_START = "periodStart";
    public static final String REVISION = "revision";

    public WeeklyRankingTarget {
        if (periodStart == null || periodStart.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new IllegalArgumentException("periodStart 는 UTC 월요일이어야 합니다. [periodStart = " + periodStart + "]");
        }
        if (revision < 1) {
            throw new IllegalArgumentException("revision 은 1 이상이어야 합니다. [revision = " + revision + "]");
        }
    }

    public static WeeklyRankingTarget of(String periodStart, String revision) {
        if (periodStart == null || revision == null) {
            throw new IllegalArgumentException("periodStart·revision 이 모두 필요합니다. [periodStart = " + periodStart + ", revision = " + revision + "]");
        }
        LocalDate start;
        long parsedRevision;
        try {
            start = LocalDate.parse(periodStart);
            parsedRevision = Long.parseLong(revision);
        } catch (DateTimeParseException | NumberFormatException e) {
            throw new IllegalArgumentException("periodStart(yyyy-MM-dd)·revision(정수) 형식이 아닙니다. [periodStart = "
                + periodStart + ", revision = " + revision + "]", e);
        }
        WeeklyRankingTarget target = new WeeklyRankingTarget(start, parsedRevision);
        if (!target.periodStart.toString().equals(periodStart) || !String.valueOf(target.revision).equals(revision)) {
            throw new IllegalArgumentException("정규 표기가 아닙니다. [periodStart = " + periodStart + ", revision = " + revision + "]");
        }
        return target;
    }

    public static WeeklyRankingTarget from(JobParameters parameters) {
        return of(parameters.getString(PERIOD_START), parameters.getString(REVISION));
    }

    /** 두 값 모두 식별 parameter. 실행마다 바뀌는 값(run.id·시각)은 넣지 않는다. */
    public JobParameters toJobParameters() {
        return new JobParametersBuilder()
            .addJobParameter(PERIOD_START, new JobParameter<>(periodStart.toString(), String.class, true))
            .addJobParameter(REVISION, new JobParameter<>(String.valueOf(revision), String.class, true))
            .toJobParameters();
    }

    public LocalDate periodEnd() {
        return periodStart.plusWeeks(1);
    }

    /** 구간 시작 (포함). DB 의 UTC datetime 값과 그대로 비교한다. */
    public LocalDateTime startUtc() {
        return periodStart.atStartOfDay();
    }

    /** 구간 끝 (제외). */
    public LocalDateTime endUtc() {
        return periodEnd().atStartOfDay();
    }

    public Instant endInstant() {
        return endUtc().toInstant(ZoneOffset.UTC);
    }

    public String snapshotId() {
        return snapshotIdOf(periodStart, revision);
    }

    public static String snapshotIdOf(LocalDate periodStart, long revision) {
        return "weekly:" + periodStart + ":r" + revision;
    }
}
