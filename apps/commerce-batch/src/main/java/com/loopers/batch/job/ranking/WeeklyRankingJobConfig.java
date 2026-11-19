package com.loopers.batch.job.ranking;

import com.loopers.batch.job.ranking.freeze.FreezeInputTasklet;
import com.loopers.batch.job.ranking.publish.PublishRankingTasklet;
import com.loopers.batch.job.ranking.score.CompleteScoresTasklet;
import com.loopers.batch.job.ranking.score.ProductKey;
import com.loopers.batch.job.ranking.score.ProductScore;
import com.loopers.batch.job.ranking.score.WeeklyScoreProcessor;
import com.loopers.batch.job.ranking.score.WeeklyScoreWriter;
import com.loopers.batch.listener.JobListener;
import com.loopers.batch.listener.StepMonitorListener;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * 주간 구매 랭킹 job. 입력 고정 → 점수 계산 → 계산 완료 → 공개 (9주차 설계).
 * 실패 재시작이 같은 JobInstance 로 이어지도록 incrementer 를 두지 않고, 업무 DB 와 같은 transaction manager 를 쓴다
 */
@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = WeeklyRankingJobConfig.JOB_NAME)
@RequiredArgsConstructor
@Configuration
public class WeeklyRankingJobConfig {

    public static final String JOB_NAME = "weeklyRanking";
    public static final String FREEZE_STEP = "freezeWeeklyInput";
    public static final String SCORE_STEP = "calculateWeeklyScores";
    public static final String COMPLETE_STEP = "completeWeeklyScores";
    public static final String PUBLISH_STEP = "publishWeeklyRanking";
    public static final String PRODUCT_READER = "weeklyProductReader";

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final JobListener jobListener;
    private final StepMonitorListener stepMonitorListener;
    private final RejectAbandonedSnapshotListener rejectAbandonedSnapshotListener;
    private final FreezeInputTasklet freezeInputTasklet;
    private final WeeklyScoreProcessor weeklyScoreProcessor;
    private final WeeklyScoreWriter weeklyScoreWriter;
    private final CompleteScoresTasklet completeScoresTasklet;
    private final PublishRankingTasklet publishRankingTasklet;

    /** 재시작 실험(실습 §6)은 2 를 쓴다. 기본값은 측정한 최적값이 아니다 */
    @Value("${weekly-ranking.chunk-size:100}")
    private int chunkSize;

    @Bean(JOB_NAME)
    public Job weeklyRankingJob() {
        return new JobBuilder(JOB_NAME, jobRepository)
            .validator(new WeeklyRankingParametersValidator())
            .start(freezeWeeklyInputStep())
            .next(calculateWeeklyScoresStep())
            .next(completeWeeklyScoresStep())
            .next(publishWeeklyRankingStep())
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

    @Bean(SCORE_STEP)
    public Step calculateWeeklyScoresStep() {
        return new StepBuilder(SCORE_STEP, jobRepository)
            .<ProductKey, ProductScore>chunk(chunkSize, transactionManager)
            .reader(weeklyProductReader(null, null))
            .processor(weeklyScoreProcessor)
            .writer(weeklyScoreWriter)
            .listener(rejectAbandonedSnapshotListener)
            .listener(stepMonitorListener)
            .build();
    }

    @Bean(COMPLETE_STEP)
    public Step completeWeeklyScoresStep() {
        return new StepBuilder(COMPLETE_STEP, jobRepository)
            .tasklet(completeScoresTasklet, transactionManager)
            .listener(rejectAbandonedSnapshotListener)
            .listener(stepMonitorListener)
            .build();
    }

    @Bean(PUBLISH_STEP)
    public Step publishWeeklyRankingStep() {
        return new StepBuilder(PUBLISH_STEP, jobRepository)
            .tasklet(publishRankingTasklet, transactionManager)
            .listener(rejectAbandonedSnapshotListener)
            .listener(stepMonitorListener)
            .build();
    }

    /** 고정 입력의 서로 다른 상품을 id 순으로 읽는다. 입력이 바뀌지 않아 재시작 때 같은 순서 · 같은 위치가 된다 */
    @Bean(PRODUCT_READER)
    @StepScope
    public JdbcCursorItemReader<ProductKey> weeklyProductReader(
        DataSource dataSource,
        @Value("#{jobParameters['snapshotId']}") String snapshotId
    ) {
        return new JdbcCursorItemReaderBuilder<ProductKey>()
            .name(PRODUCT_READER)
            .saveState(true)
            .dataSource(dataSource)
            .sql("select distinct product_id from weekly_ranking_input where snapshot_id = ? order by product_id")
            .preparedStatementSetter(ps -> ps.setString(1, snapshotId))
            .rowMapper((rs, rowNum) -> new ProductKey(snapshotId, rs.getLong("product_id")))
            .build();
    }
}
