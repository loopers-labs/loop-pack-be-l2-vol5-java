package com.loopers.batch.job.weeklyranking;

/** weeklyRanking job 의 식별 parameter 이름. 실행 시각·run.id 는 넣지 않는다. */
public final class WeeklyRankingJobParameters {

    public static final String PERIOD_START = "periodStart";
    public static final String REVISION = "revision";

    private WeeklyRankingJobParameters() {}
}
