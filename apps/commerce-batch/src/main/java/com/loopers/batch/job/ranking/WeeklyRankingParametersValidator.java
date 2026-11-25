package com.loopers.batch.job.ranking;

import java.time.LocalDate;
import java.util.Map;

import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;

class WeeklyRankingParametersValidator implements JobParametersValidator {

    private final IdentifyingParametersValidator signature = new IdentifyingParametersValidator(Map.of(
        "periodStart", LocalDate.class,
        "revision", Long.class,
        "snapshotId", String.class
    ));

    @Override
    public void validate(JobParameters parameters) throws JobParametersInvalidException {
        signature.validate(parameters);
        try {
            WeeklyRankingParameters requested = WeeklyRankingParameters.from(parameters);
            requested.snapshot().requireId(requested.snapshotId());
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new JobParametersInvalidException(e.getMessage());
        }
    }
}
