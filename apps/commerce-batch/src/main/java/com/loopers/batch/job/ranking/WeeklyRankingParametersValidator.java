package com.loopers.batch.job.ranking;

import org.springframework.batch.core.JobParameter;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;

/**
 * JobInstance 를 만들기 전에 식별 parameter 를 검사한다. 잘못된 값으로는 실행 기록도 남기지 않는다.
 * periodStart·revision 은 식별 parameter 여야 한다: 아니면 다른 기간·revision 이 같은 JobInstance 로 묶인다.
 */
public class WeeklyRankingParametersValidator implements JobParametersValidator {

    @Override
    public void validate(JobParameters parameters) throws JobParametersInvalidException {
        if (parameters == null) {
            throw new JobParametersInvalidException("job parameter 가 없습니다.");
        }
        for (String key : new String[]{WeeklyRankingTarget.PERIOD_START, WeeklyRankingTarget.REVISION}) {
            JobParameter<?> parameter = parameters.getParameter(key);
            if (parameter == null || !parameter.isIdentifying() || parameter.getType() != String.class) {
                throw new JobParametersInvalidException(key + " 는 식별 String parameter 여야 합니다. [" + key + " = " + parameter + "]");
            }
        }
        try {
            WeeklyRankingTarget.from(parameters);
        } catch (IllegalArgumentException e) {
            throw new JobParametersInvalidException(e.getMessage());
        }
    }
}
