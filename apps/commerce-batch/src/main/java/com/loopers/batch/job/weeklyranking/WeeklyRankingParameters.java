package com.loopers.batch.job.weeklyranking;

import com.loopers.domain.ranking.RankingInputException;
import com.loopers.domain.ranking.WeeklyRankingRequest;
import org.springframework.batch.core.JobParameter;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;

/** weeklyRanking 의 세 identifying parameter 와 요청 값 사이의 변환. validator 와 Step 이 같은 변환을 쓴다. */
public final class WeeklyRankingParameters {

    public static final String PERIOD_START = "periodStart";
    public static final String REVISION = "revision";
    public static final String SNAPSHOT_ID = "snapshotId";

    public static final JobParametersValidator VALIDATOR = parameters -> {
        try {
            parse(parameters);
        } catch (RankingInputException e) {
            throw new JobParametersInvalidException(e.getMessage());
        }
    };

    private WeeklyRankingParameters() {}

    public static WeeklyRankingRequest parse(JobParameters parameters) {
        return WeeklyRankingRequest.of(
            identifying(parameters, PERIOD_START, String.class),
            identifying(parameters, REVISION, Long.class),
            identifying(parameters, SNAPSHOT_ID, String.class));
    }

    public static JobParameters toJobParameters(WeeklyRankingRequest request) {
        return new JobParametersBuilder()
            .addString(PERIOD_START, request.periodStart().toString(), true)
            .addLong(REVISION, request.revision(), true)
            .addString(SNAPSHOT_ID, request.snapshotId(), true)
            .toJobParameters();
    }

    private static <T> T identifying(JobParameters parameters, String name, Class<T> type) {
        JobParameter<?> parameter = parameters.getParameter(name);
        if (parameter == null || !parameter.isIdentifying() || !type.equals(parameter.getType())) {
            throw new RankingInputException(RankingInputException.Reason.INVALID_REQUEST,
                name + " 는 identifying " + type.getSimpleName() + " parameter 여야 합니다.");
        }
        return type.cast(parameter.getValue());
    }
}
