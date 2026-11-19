package com.loopers.batch.job.ranking;

import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;

/**
 * 랭킹 테스트의 공통 준비. DB 시각은 UTC 날짜·시각이다.
 */
public final class WeeklyRankingTestSupport {

    private WeeklyRankingTestSupport() {
    }

    public static JobParameters parameters(String periodStart, String revision) {
        return new JobParametersBuilder()
            .addString(WeeklyRankingKey.PERIOD_START, periodStart)
            .addString(WeeklyRankingKey.REVISION, revision)
            .toJobParameters();
    }
}
