package com.loopers.job.ranking;

import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.ranking.WeeklyRankingStore;
import com.loopers.batch.job.ranking.WeeklyRankingTarget;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * weeklyRanking job 테스트 공통. 자동 실행을 끄고 같은 job bean 을 기본 동기 JobLauncher 로 직접 실행한다.
 * 실패 주입은 job parameter 가 아니라 저장소 spy 로 한다 (테스트마다 Mockito 가 되돌린다). 시계는 2026-12-01T00:00Z 고정.
 * chunk 는 재시작 실험값 2. Batch metadata·랭킹 테이블은 테스트 사이에도 지우지 않는다: 테스트마다 다른 주를 쓴다.
 */
@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
    "ranking.weekly.chunk-size=2"
})
@Import(WeeklyRankingJobTestSupport.FixedClock.class)
abstract class WeeklyRankingJobTestSupport {
    static final Instant NOW = Instant.parse("2026-12-01T00:00:00Z");

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    protected JobLauncher jobLauncher;
    @Autowired
    @Qualifier(WeeklyRankingJobConfig.JOB_NAME)
    protected Job job;
    @Autowired
    protected JobExplorer jobExplorer;
    @Autowired
    protected JobRepository jobRepository;
    @Autowired
    @Qualifier("mySqlMainDataSource")
    protected DataSource dataSource;
    @MockitoSpyBean
    protected WeeklyRankingStore store;

    protected RankingTestDb db;

    @BeforeEach
    void prepareDb() {
        db = new RankingTestDb(dataSource);
        db.prepareSchema();
    }

    protected static WeeklyRankingTarget target(String periodStart, long revision) {
        return new WeeklyRankingTarget(LocalDate.parse(periodStart), revision);
    }

    protected JobExecution launch(WeeklyRankingTarget target) throws Exception {
        return jobLauncher.run(job, target.toJobParameters());
    }

    /** 실행 객체가 아니라 JobRepository 에 저장된 step 실행을 다시 읽는다. 없으면 null. */
    protected StepExecution persistedStep(JobExecution execution, String stepName) {
        return execution.getStepExecutions().stream()
            .filter(step -> step.getStepName().equals(stepName))
            .findFirst()
            .map(step -> jobExplorer.getStepExecution(execution.getId(), step.getId()))
            .orElse(null);
    }
}
