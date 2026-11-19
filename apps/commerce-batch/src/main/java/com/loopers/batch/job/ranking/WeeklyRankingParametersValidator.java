package com.loopers.batch.job.ranking;

import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;

/** 세 식별 파라미터가 서로 맞지 않으면 JobInstance 를 만들기 전에 거절한다 (9주차 D-186) */
public class WeeklyRankingParametersValidator implements JobParametersValidator {

    @Override
    public void validate(JobParameters parameters) throws JobParametersInvalidException {
        if (parameters == null) {
            throw new JobParametersInvalidException("job parameter 가 없음");
        }
        try {
            WeeklyRankingParameters.from(parameters);
        } catch (IllegalArgumentException e) {
            throw new JobParametersInvalidException(e.getMessage());
        }
    }
}
