package com.loopers.batch.job.ranking;

import org.springframework.batch.core.JobParameter;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 주간 랭킹 Job의 식별 파라미터 검사. JobLauncher가 JobInstance를 만들기 전에 부르므로, 어기면 실행 기록도 생기지 않는다.
 * <ul>
 *   <li>periodStart, revision 두 개만 받는다. 모르는 키는 거절한다.</li>
 *   <li>둘 다 타입 없는 문자열 식별 파라미터여야 한다. Spring Batch는 타입까지 JobInstance 식별에 쓴다.</li>
 *   <li>periodStart는 yyyy-MM-dd 모양의 월요일, revision은 0으로 시작하지 않는 양의 정수다.</li>
 * </ul>
 */
@Component
public class WeeklyRankingParametersValidator implements JobParametersValidator {

    private static final Set<String> NAMES = Set.of(WeeklyRankingKey.PERIOD_START, WeeklyRankingKey.REVISION);

    @Override
    public void validate(JobParameters parameters) throws JobParametersInvalidException {
        Map<String, JobParameter<?>> values = parameters == null ? Map.of() : parameters.getParameters();
        for (String name : values.keySet()) {
            if (!NAMES.contains(name)) {
                throw new JobParametersInvalidException("모르는 잡 파라미터다. name=" + name);
            }
        }
        for (String name : NAMES) {
            JobParameter<?> parameter = values.get(name);
            if (parameter == null) {
                throw new JobParametersInvalidException("잡 파라미터가 없다. name=" + name);
            }
            if (!parameter.isIdentifying() || parameter.getType() != String.class) {
                throw new JobParametersInvalidException("문자열 식별 파라미터여야 한다. name=" + name);
            }
        }
        try {
            WeeklyRankingKey.from(parameters);
        } catch (IllegalArgumentException e) {
            throw new JobParametersInvalidException(e.getMessage());
        }
    }
}
