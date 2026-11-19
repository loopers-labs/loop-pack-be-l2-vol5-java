package com.loopers.job.ranking;

import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.ranking.step.FreezeWeeklyInputTasklet;
import com.loopers.batch.job.ranking.step.OpenWeeklyRankingTasklet;
import com.loopers.batch.job.ranking.step.WeeklyScoreStepComponents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.SimpleJob;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * weeklyRanking Job 의 재시작 검증. ⑤에서는 조립 확인만 두고, ⑥에서 과제 §6 재시작 시나리오를 이 클래스에 채운다.
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME})
class WeeklyRankingJobRestartTest {

    private final Job job;
    private final JobLauncher jobLauncher;
    private final JobExplorer jobExplorer;
    private final JdbcTemplate jdbcTemplate;
    private final PlatformTransactionManager transactionManager;

    @Autowired
    WeeklyRankingJobRestartTest(
        @Qualifier(WeeklyRankingJobConfig.JOB_NAME) Job job,
        JobLauncher jobLauncher,
        JobExplorer jobExplorer,
        JdbcTemplate jdbcTemplate,
        PlatformTransactionManager transactionManager
    ) {
        this.job = job;
        this.jobLauncher = jobLauncher;
        this.jobExplorer = jobExplorer;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionManager = transactionManager;
    }

    @DisplayName("weeklyRanking Job 은 입력 고정 → 점수 계산 → 공개 세 Step 을 순서대로 가진다.")
    @Test
    void assemblesThreeStepsInOrder() {
        assertThat(job.getName()).isEqualTo(WeeklyRankingJobConfig.JOB_NAME);
        assertThat(job).isInstanceOf(SimpleJob.class);
        assertThat(((SimpleJob) job).getStepNames()).containsExactly(
            FreezeWeeklyInputTasklet.STEP_NAME,
            WeeklyScoreStepComponents.STEP_NAME,
            OpenWeeklyRankingTasklet.STEP_NAME
        );
        assertThat(job.isRestartable()).isTrue();
        assertThat(job.getJobParametersIncrementer()).isNull();
    }
}
