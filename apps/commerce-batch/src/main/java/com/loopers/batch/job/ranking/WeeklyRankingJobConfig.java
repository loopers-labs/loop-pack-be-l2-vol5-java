package com.loopers.batch.job.ranking;

import com.loopers.batch.job.ranking.step.FreezeWeeklyInputTasklet;
import com.loopers.batch.listener.JobListener;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 주간 구매 랭킹 job (docs/week9/design.md).
 * <ul>
 *   <li>식별 parameter는 periodStart·snapshotId뿐이다. RunIdIncrementer를 쓰지 않으므로, 실패한 실행은 같은 parameter로 같은 JobInstance를 이어 간다.</li>
 *   <li>모든 step은 Boot의 실제 DB 트랜잭션 매니저를 쓴다. 결과 쓰기와 Batch metadata(checkpoint)가 같은 DataSource에서 함께 commit된다.</li>
 *   <li>StepMonitorListener는 붙이지 않는다. 실패가 없으면 exit status를 COMPLETED로 덮어써서, step이 남긴 상태(ALREADY_FROZEN 등)를 지운다.</li>
 * </ul>
 */
@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = WeeklyRankingJobConfig.JOB_NAME)
@Configuration
public class WeeklyRankingJobConfig {

    public static final String JOB_NAME = "weeklyRanking";
    public static final String FREEZE_STEP = "freezeWeeklyInput";

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final JobListener jobListener;

    public WeeklyRankingJobConfig(JobRepository jobRepository, PlatformTransactionManager transactionManager, JobListener jobListener) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.jobListener = jobListener;
    }

    @Bean(JOB_NAME)
    public Job weeklyRankingJob(Step freezeWeeklyInputStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
            .validator(parametersValidator())
            .start(freezeWeeklyInputStep)
            .listener(jobListener)
            .build();
    }

    @Bean(FREEZE_STEP)
    public Step freezeWeeklyInputStep(FreezeWeeklyInputTasklet freezeWeeklyInputTasklet) {
        return new StepBuilder(FREEZE_STEP, jobRepository)
            .tasklet(freezeWeeklyInputTasklet, transactionManager)
            .build();
    }

    private JobParametersValidator parametersValidator() {
        return parameters -> {
            if (parameters == null) {
                throw new JobParametersInvalidException("periodStart와 snapshotId가 필요합니다.");
            }
            try {
                WeeklySnapshotKey.of(parameters.getString("periodStart"), parameters.getString("snapshotId"));
            } catch (IllegalArgumentException e) {
                throw new JobParametersInvalidException(e.getMessage());
            }
        };
    }
}
