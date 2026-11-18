package com.loopers.batch.job.weeklyranking;

import org.springframework.batch.core.JobParameter;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * 작업 식별 parameter 검사 (W9 D-1). 실행 기록을 만들기 전에 거절하므로 metadata도 남지 않는다.
 * - periodStart: LocalDate, UTC 주의 시작인 월요일
 * - revision: Long, 1 이상
 */
public class WeeklyRankingJobParametersValidator implements JobParametersValidator {

    public static final String PERIOD_START = "periodStart";
    public static final String REVISION = "revision";

    @Override
    public void validate(JobParameters parameters) throws JobParametersInvalidException {
        if (parameters == null) {
            throw new JobParametersInvalidException("periodStart·revision이 필요합니다.");
        }
        LocalDate periodStart = value(parameters, PERIOD_START, LocalDate.class);
        if (periodStart.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new JobParametersInvalidException("periodStart는 월요일이어야 합니다. [periodStart = " + periodStart + "]");
        }
        Long revision = value(parameters, REVISION, Long.class);
        if (revision < 1) {
            throw new JobParametersInvalidException("revision은 1 이상이어야 합니다. [revision = " + revision + "]");
        }
    }

    private static <T> T value(JobParameters parameters, String name, Class<T> type) throws JobParametersInvalidException {
        JobParameter<?> parameter = parameters.getParameter(name);
        if (parameter == null || !type.isInstance(parameter.getValue())) {
            throw new JobParametersInvalidException(name + "(" + type.getSimpleName() + ")가 필요합니다.");
        }
        return type.cast(parameter.getValue());
    }
}
