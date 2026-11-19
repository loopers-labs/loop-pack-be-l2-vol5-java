package com.loopers.batch.job.ranking;

import org.springframework.batch.core.JobParameters;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 작업 식별값 (기간, revision). 같은 식별값으로 다시 실행하면 같은 작업이고, revision을 올리면 재계산이다.
 * 잡 파라미터는 타입 없는 문자열로만 받는다. Spring Batch는 파라미터 타입까지 JobInstance 식별에 쓰기 때문이다.
 */
public record WeeklyRankingKey(RankingPeriod period, int revision) {

    public static final String PERIOD_START = "periodStart";
    public static final String REVISION = "revision";

    // 1과 01처럼 같은 뜻의 다른 모양이 다른 작업이 되지 않게 한 가지 모양만 받는다.
    private static final Pattern REVISION_SHAPE = Pattern.compile("[1-9][0-9]{0,9}");

    public WeeklyRankingKey {
        Objects.requireNonNull(period, "period");
        if (revision < 1) {
            throw new IllegalArgumentException("revision은 1부터다. revision=" + revision);
        }
    }

    public static WeeklyRankingKey from(JobParameters jobParameters) {
        Map<String, Object> values = new HashMap<>();
        jobParameters.getParameters().forEach((name, parameter) -> values.put(name, parameter.getValue()));
        return from(values);
    }

    static WeeklyRankingKey from(Map<String, Object> jobParameters) {
        Object periodStart = jobParameters.get(PERIOD_START);
        Object revision = jobParameters.get(REVISION);
        if (!(periodStart instanceof String period) || !(revision instanceof String number)) {
            throw new IllegalArgumentException("periodStart와 revision은 문자열 잡 파라미터여야 한다. parameters=" + jobParameters);
        }
        return new WeeklyRankingKey(RankingPeriod.parse(period), parseRevision(number));
    }

    static int parseRevision(String value) {
        if (value == null || !REVISION_SHAPE.matcher(value).matches()) {
            throw new IllegalArgumentException("revision은 0으로 시작하지 않는 양의 정수여야 한다. value=" + value);
        }
        long revision = Long.parseLong(value);
        if (revision > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("revision이 너무 크다. value=" + value);
        }
        return (int) revision;
    }
}
