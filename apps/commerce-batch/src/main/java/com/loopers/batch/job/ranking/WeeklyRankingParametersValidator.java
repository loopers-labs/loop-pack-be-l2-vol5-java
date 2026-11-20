package com.loopers.batch.job.ranking;

import static java.util.stream.Collectors.toMap;

import java.time.LocalDate;
import java.util.Map;

import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;

class WeeklyRankingParametersValidator implements JobParametersValidator {

    private static final Map<String, Class<?>> SIGNATURE = Map.of(
        "periodStart", LocalDate.class,
        "revision", Long.class,
        "snapshotId", String.class
    );

    @Override
    public void validate(JobParameters parameters) throws JobParametersInvalidException {
        JobParameters given = parameters == null ? new JobParameters() : parameters;
        Map<String, Class<?>> identifying = given.getIdentifyingParameters().entrySet().stream()
            .collect(toMap(Map.Entry::getKey, entry -> entry.getValue().getType()));
        if (given.getParameters().size() != identifying.size() || !identifying.equals(SIGNATURE)) {
            throw new JobParametersInvalidException(
                "weeklyRanking takes exactly the identifying parameters " + SIGNATURE + ": " + given
            );
        }
        try {
            WeeklyRankingParameters requested = WeeklyRankingParameters.from(given);
            requested.snapshot().requireId(requested.snapshotId());
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new JobParametersInvalidException(e.getMessage());
        }
    }
}
