package com.loopers.batch.job.ranking;

import org.springframework.batch.core.JobParameter;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;

import java.util.Map;
import java.util.Set;

public class WeeklyRankingParametersValidator implements JobParametersValidator {
    private static final Set<String> IDENTIFYING = Set.of(
            WeeklyRankingParameters.PERIOD_START, WeeklyRankingParameters.SNAPSHOT_ID);

    @Override
    public void validate(JobParameters parameters) throws JobParametersInvalidException {
        if (parameters == null) {
            throw new JobParametersInvalidException("periodStart and snapshotId are required");
        }
        for (String name : IDENTIFYING) {
            JobParameter<?> parameter = parameters.getParameter(name);
            if (parameter == null || !parameter.isIdentifying() || !(parameter.getValue() instanceof String)) {
                throw new JobParametersInvalidException(name + " must be an identifying String parameter");
            }
        }
        for (Map.Entry<String, JobParameter<?>> entry : parameters.getParameters().entrySet()) {
            if (entry.getValue().isIdentifying() && !IDENTIFYING.contains(entry.getKey())) {
                throw new JobParametersInvalidException("unexpected identifying parameter: " + entry.getKey());
            }
        }
        try {
            WeeklyRankingParameters.from(parameters);
        } catch (IllegalArgumentException e) {
            throw new JobParametersInvalidException(e.getMessage());
        }
    }
}
