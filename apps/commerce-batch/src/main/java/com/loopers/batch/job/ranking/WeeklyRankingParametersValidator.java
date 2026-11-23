package com.loopers.batch.job.ranking;

import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameter;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;
import org.springframework.batch.core.explore.JobExplorer;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class WeeklyRankingParametersValidator implements JobParametersValidator {
    private static final Set<String> IDENTIFYING = Set.of(
            WeeklyRankingParameters.PERIOD_START, WeeklyRankingParameters.SNAPSHOT_ID);
    private final JobExplorer explorer;

    public WeeklyRankingParametersValidator(JobExplorer explorer) {
        this.explorer = explorer;
    }

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
        var source = parameters.getParameter(WeeklyRankingParameters.SOURCE_SNAPSHOT_ID);
        if (source != null && !(source.getValue() instanceof String)) {
            throw new JobParametersInvalidException("sourceSnapshotId must be a non-identifying String parameter");
        }
        try {
            WeeklyRankingParameters.from(parameters);
        } catch (IllegalArgumentException e) {
            throw new JobParametersInvalidException(e.getMessage());
        }
        var instance = explorer.getJobInstance(WeeklyRankingJobConfig.JOB_NAME, parameters);
        if (instance != null) {
            var first = explorer.getJobExecutions(instance).stream().min(Comparator.comparing(JobExecution::getId));
            if (first.isPresent() && !Objects.equals(source,
                    first.get().getJobParameters().getParameter(WeeklyRankingParameters.SOURCE_SNAPSHOT_ID))) {
                throw new JobParametersInvalidException("sourceSnapshotId must match the first execution on restart");
            }
        }
    }
}
