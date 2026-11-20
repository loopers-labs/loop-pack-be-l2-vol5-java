package com.loopers.batch.job.weeklyranking;

import com.loopers.domain.ranking.WeeklyRankingKey;
import org.springframework.batch.core.JobParameter;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;

public final class WeeklyRankingJobParameters implements JobParametersValidator {
    private static final Set<String> IDENTITY_FIELDS = Set.of("periodStart", "revision", "snapshotId");

    public static WeeklyRankingKey key(JobParameters parameters) {
        if (parameters == null || !parameters.getParameters().keySet().equals(IDENTITY_FIELDS)) {
            throw new IllegalArgumentException("Weekly ranking requires exactly periodStart, revision and snapshotId");
        }
        String periodStart = identifyingValue(parameters, "periodStart", String.class);
        long revision = identifyingValue(parameters, "revision", Long.class);
        String snapshotId = identifyingValue(parameters, "snapshotId", String.class);
        if (revision <= 0 || revision > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Weekly ranking revision must be a positive int-range Long");
        }
        LocalDate date;
        try {
            date = LocalDate.parse(periodStart, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("Weekly ranking periodStart must be a canonical ISO date");
        }
        if (!date.toString().equals(periodStart)) {
            throw new IllegalArgumentException("Weekly ranking periodStart must be a canonical ISO date");
        }
        var key = new WeeklyRankingKey(date, (int) revision);
        if (!snapshotId.equals(key.snapshotId())) {
            throw new IllegalArgumentException("Weekly ranking snapshotId must match periodStart and revision");
        }
        return key;
    }

    public static JobParameters parameters(WeeklyRankingKey key) {
        if (key == null) {
            throw new IllegalArgumentException("Weekly ranking identity is required");
        }
        return new JobParametersBuilder()
            .addString("periodStart", key.periodStart().toString(), true)
            .addLong("revision", (long) key.revision(), true)
            .addString("snapshotId", key.snapshotId(), true)
            .toJobParameters();
    }

    @Override
    public void validate(JobParameters parameters) throws JobParametersInvalidException {
        try {
            key(parameters);
        } catch (IllegalArgumentException exception) {
            throw new JobParametersInvalidException(
                "weeklyRanking requires matching, identifying periodStart(String), revision(Long) and snapshotId(String)");
        }
    }

    private static <T> T identifyingValue(JobParameters parameters, String name, Class<T> type) {
        JobParameter<?> parameter = parameters.getParameter(name);
        if (parameter == null || !parameter.isIdentifying() || parameter.getType() != type
            || !type.isInstance(parameter.getValue())) {
            throw new IllegalArgumentException("Weekly ranking parameters must have their exact types and be identifying");
        }
        return type.cast(parameter.getValue());
    }
}
