package com.loopers.batch.job.ranking;

import com.loopers.batch.job.ranking.step.FreezeInputTasklet;
import com.loopers.batch.listener.JobListener;
import com.loopers.batch.listener.StepMonitorListener;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 주간 구매 랭킹 job. 입력 고정 → 점수 계산 → 공개 (9주차 설계).
 * 실패 재시작이 같은 JobInstance 로 이어지도록 incrementer 를 두지 않고, 업무 DB 와 같은 transaction manager 를 쓴다
 */
@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = WeeklyRankingJobConfig.JOB_NAME)
@RequiredArgsConstructor
@Configuration
public class WeeklyRankingJobConfig {

    public static final String JOB_NAME = "weeklyRanking";
    public static final String FREEZE_STEP = "freezeWeeklyInput";

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final JobListener jobListener;
    private final StepMonitorListener stepMonitorListener;
    private final FreezeInputTasklet freezeInputTasklet;

    @Bean(JOB_NAME)
    public Job weeklyRankingJob() {
        return new JobBuilder(JOB_NAME, jobRepository)
            .validator(new WeeklyRankingParametersValidator())
            .start(freezeWeeklyInputStep())
            .listener(jobListener)
            .build();
    }

    @Bean(FREEZE_STEP)
    public Step freezeWeeklyInputStep() {
        return new StepBuilder(FREEZE_STEP, jobRepository)
            .tasklet(freezeInputTasklet, transactionManager)
            .listener(stepMonitorListener)
            .build();
    }
}
