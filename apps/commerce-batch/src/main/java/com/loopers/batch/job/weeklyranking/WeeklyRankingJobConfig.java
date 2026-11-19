package com.loopers.batch.job.weeklyranking;

import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.DefaultJobParametersValidator;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;

/**
 * 입력 고정 → 점수 계산 → 완성본 공개. 식별 parameter 는 periodStart·revision 이고 incrementer 를 두지 않는다.
 * 실패하면 같은 parameter 로 다시 실행해 같은 JobInstance 의 마지막 commit 부터 이어 간다.
 */
@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = WeeklyRankingJobConfig.JOB_NAME)
@RequiredArgsConstructor
@Configuration
public class WeeklyRankingJobConfig {

    public static final String JOB_NAME = "weeklyRanking";
    public static final String FREEZE_STEP = "freezeWeeklyInput";

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final FreezeInputTasklet freezeInputTasklet;

    @Bean(JOB_NAME)
    public Job weeklyRankingJob() {
        return new JobBuilder(JOB_NAME, jobRepository)
            .validator(new DefaultJobParametersValidator(
                new String[] {WeeklyRankingJobParameters.PERIOD_START, WeeklyRankingJobParameters.REVISION},
                new String[] {}))
            .start(freezeInputStep())
            .build();
    }

    @Bean(FREEZE_STEP)
    public Step freezeInputStep() {
        // 원본을 잠그지 않는 일관된 읽기로 복사해 W6 consumer 의 insert 를 막지 않는다.
        DefaultTransactionAttribute readCommitted = new DefaultTransactionAttribute();
        readCommitted.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return new StepBuilder(FREEZE_STEP, jobRepository)
            .tasklet(freezeInputTasklet, transactionManager)
            .transactionAttribute(readCommitted)
            .build();
    }
}
