package com.loopers.batch.runner;

import com.loopers.batch.job.weeklyranking.WeeklyRankingJobConfig;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.batch.JobLauncherApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * weeklyRankingJob을 띄울 때만 Boot의 runner 대신 WeeklyRankingJobRunner를 쓴다 (W9 D-8).
 * Boot는 JobLauncherApplicationRunner bean이 이미 있으면 자기 것을 만들지 않는다 (@ConditionalOnMissingBean).
 * 자동 실행을 끄면(spring.batch.job.enabled=false, 테스트) 이 runner도 만들지 않는다.
 */
@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = WeeklyRankingJobConfig.JOB_NAME)
@Configuration
public class WeeklyRankingRunnerConfig {

    @Bean
    @ConditionalOnProperty(prefix = "spring.batch.job", name = "enabled", havingValue = "true", matchIfMissing = true)
    public JobLauncherApplicationRunner weeklyRankingJobRunner(
        JobLauncher jobLauncher,
        JobExplorer jobExplorer,
        JobRepository jobRepository,
        WeeklyRankingRevisionResolver revisionResolver,
        @Value("${recompute:false}") boolean recompute
    ) {
        WeeklyRankingJobRunner runner = new WeeklyRankingJobRunner(jobLauncher, jobExplorer, jobRepository, revisionResolver, recompute);
        runner.setJobName(WeeklyRankingJobConfig.JOB_NAME);
        return runner;
    }
}
