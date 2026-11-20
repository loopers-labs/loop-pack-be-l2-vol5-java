package com.loopers.batch.job.ranking;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.loopers.batch.job.ranking.step.CountLateArrivalsTasklet;
import com.loopers.batch.job.ranking.store.PublishedRankingStore;
import com.loopers.batch.job.ranking.store.SnapshotStore;

@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = WeeklyRankingLateArrivalsJobConfig.JOB_NAME)
@Configuration
class WeeklyRankingLateArrivalsJobConfig {

    static final String JOB_NAME = "weeklyRankingLateArrivals";

    @Bean(JOB_NAME)
    Job weeklyRankingLateArrivals(
        JobRepository jobRepository, PlatformTransactionManager transactionManager, DataSource dataSource
    ) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        return new JobBuilder(JOB_NAME, jobRepository)
            .validator(new IdentifyingParametersValidator(Map.of(
                "periodStart", LocalDate.class,
                "checkedAt", LocalDateTime.class
            )))
            .start(new StepBuilder("countLateArrivals", jobRepository)
                .tasklet(
                    new CountLateArrivalsTasklet(new PublishedRankingStore(jdbc), new SnapshotStore(jdbc)),
                    transactionManager
                )
                .build())
            .build();
    }
}
