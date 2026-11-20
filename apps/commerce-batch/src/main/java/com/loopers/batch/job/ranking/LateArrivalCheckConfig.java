package com.loopers.batch.job.ranking;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 늦은 구매 감지 모드. weeklyRanking job 구성이 있을 때 commerce.ranking.late-check.enabled=true로만 켠다. */
@ConditionalOnProperty(name = "commerce.ranking.late-check.enabled", havingValue = "true")
@Configuration
public class LateArrivalCheckConfig {

    @Bean
    public LateArrivalRevisionRunner lateArrivalRevisionRunner(WeeklyRankingStore store, JobLauncher jobLauncher,
        @Qualifier(WeeklyRankingJobConfig.JOB_NAME) Job weeklyRankingJob, ApplicationEventPublisher events) {
        return new LateArrivalRevisionRunner(new LateArrivalDetector(store, Clock.systemUTC()), jobLauncher,
            weeklyRankingJob, events);
    }
}
