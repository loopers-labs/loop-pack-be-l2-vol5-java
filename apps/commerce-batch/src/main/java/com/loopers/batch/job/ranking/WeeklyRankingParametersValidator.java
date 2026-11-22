package com.loopers.batch.job.ranking;

import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * 식별 parameter는 periodStart(yyyy-MM-dd)와 snapshotId 두 개다.
 * snapshotId의 날짜가 periodStart와 같아야 한다. run.id·실행 시각 같은 다른 parameter는 받지 않는다.
 */
public class WeeklyRankingParametersValidator implements JobParametersValidator {
    public static final String PERIOD_START = "periodStart";
    public static final String SNAPSHOT_ID = "snapshotId";

    @Override
    public void validate(JobParameters parameters) throws JobParametersInvalidException {
        if (parameters == null) {
            throw new JobParametersInvalidException("periodStart와 snapshotId가 필요합니다.");
        }
        for (String name : parameters.getParameters().keySet()) {
            if (!name.equals(PERIOD_START) && !name.equals(SNAPSHOT_ID)) {
                throw new JobParametersInvalidException("알 수 없는 parameter입니다: " + name);
            }
        }
        if (!isIdentifying(parameters, PERIOD_START) || !isIdentifying(parameters, SNAPSHOT_ID)) {
            throw new JobParametersInvalidException("periodStart와 snapshotId는 식별 parameter여야 합니다.");
        }

        WeeklySnapshotId snapshotId;
        LocalDate periodStart;
        try {
            snapshotId = WeeklySnapshotId.parse(parameters.getString(SNAPSHOT_ID));
            periodStart = LocalDate.parse(parameters.getString(PERIOD_START));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new JobParametersInvalidException(e.getMessage());
        }
        if (!snapshotId.periodStart().equals(periodStart)) {
            throw new JobParametersInvalidException(
                "snapshotId의 날짜와 periodStart가 다릅니다: " + snapshotId.value() + ", " + periodStart);
        }
    }

    private static boolean isIdentifying(JobParameters parameters, String name) {
        var parameter = parameters.getParameter(name);
        return parameter != null && parameter.isIdentifying() && parameter.getType() == String.class;
    }
}
