package com.loopers.batch.job.ranking;

import javax.sql.DataSource;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = WeeklyRankingJobConfig.JOB_NAME)
@Configuration
class WeeklyRankingJobConfig {

    static final String JOB_NAME = "weeklyRanking";

    @Bean(JOB_NAME)
    Job weeklyRanking(JobRepository jobRepository, Step freezeSnapshotStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
            .start(freezeSnapshotStep)
            .build();
    }

    @Bean
    Step freezeSnapshotStep(
        JobRepository jobRepository, PlatformTransactionManager transactionManager, DataSource dataSource
    ) {
        return new StepBuilder("freezeSnapshot", jobRepository)
            .tasklet(new FreezeSnapshotTasklet(new JdbcTemplate(dataSource)), transactionManager)
            .build();
    }
}
