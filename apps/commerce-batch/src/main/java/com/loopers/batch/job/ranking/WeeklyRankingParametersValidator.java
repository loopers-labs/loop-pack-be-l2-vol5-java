package com.loopers.batch.job.ranking;

import com.loopers.application.ranking.command.FreezeRankingSnapshotCommand;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;

public class WeeklyRankingParametersValidator implements JobParametersValidator {
    private static final Map<String, Class<?>> TYPES = Map.of("periodStart", String.class, "revision", Long.class,
        "snapshotId", String.class);

    @Override
    public void validate(JobParameters parameters) throws JobParametersInvalidException {
        try {
            command(parameters);
        } catch (IllegalArgumentException exception) {
            throw new JobParametersInvalidException(exception.getMessage());
        }
    }

    static FreezeRankingSnapshotCommand command(JobParameters parameters) {
        if (parameters == null || !parameters.getParameters().keySet().equals(TYPES.keySet())) {
            throw new IllegalArgumentException("periodStart·revision·snapshotId 세 식별 parameter만 필요합니다.");
        }
        TYPES.forEach((name, type) -> {
            var parameter = parameters.getParameter(name);
            if (!parameter.isIdentifying() || parameter.getType() != type || parameter.getValue() == null) {
                throw new IllegalArgumentException("parameter 타입·식별 계약이 다릅니다: " + name);
            }
        });
        try {
            String id = parameters.getString("snapshotId");
            String date = parameters.getString("periodStart");
            var snapshot = UUID.fromString(id);
            var period = LocalDate.parse(date);
            long revision = parameters.getLong("revision");
            if (!snapshot.toString().equals(id) || !period.toString().equals(date)
                || revision <= 0 || revision > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("snapshotId·periodStart 정규 형식 또는 revision 범위가 다릅니다.");
            }
            return new FreezeRankingSnapshotCommand(snapshot, period, (int) revision);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("주간 실행 식별 parameter가 유효하지 않습니다.", exception);
        }
    }
}
