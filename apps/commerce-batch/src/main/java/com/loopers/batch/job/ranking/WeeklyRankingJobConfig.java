package com.loopers.batch.job.ranking;

import com.loopers.batch.job.ranking.step.FreezeInputTasklet;
import com.loopers.batch.job.ranking.step.PublishRankingTasklet;
import com.loopers.batch.job.ranking.step.ScoreStageWriter;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * 주간 구매 랭킹 job (설계 10-3): 입력 고정 → 상품별 점수 계산 → 완성본 공개.
 * <ul>
 *   <li>식별 parameter 는 periodStart·revision (문자열). incrementer 를 두지 않는다: 실패한 같은 작업은 같은 parameter 로 재시작한다.</li>
 *   <li>모든 step 은 Boot 의 JDBC JobRepository 와 같은 DB 의 transactionManager(JPA, 같은 DataSource)를 쓴다.
 *       결과 쓰기와 Batch metadata(checkpoint)가 같은 commit 에 들어간다.</li>
 *   <li>{@code spring.batch.job.name=weeklyRanking} 일 때만 만든다.</li>
 * </ul>
 * 실행: {@code --job.name=weeklyRanking periodStart=2026-08-17 revision=1}
 */
@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = WeeklyRankingJobConfig.JOB_NAME)
@RequiredArgsConstructor
@Configuration
public class WeeklyRankingJobConfig {
    public static final String JOB_NAME = "weeklyRanking";
    public static final String FREEZE_STEP = "freezeWeeklyInput";
    public static final String SCORE_STEP = "calculateWeeklyScores";
    public static final String PUBLISH_STEP = "publishWeeklyRanking";
    /** reader 이름. 재시작 상태 key 는 {@code weeklyRankingProductReader.read.count}. */
    public static final String READER_NAME = "weeklyRankingProductReader";

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final JobListener jobListener;
    private final StepMonitorListener stepMonitorListener;
    private final WeeklyRankingProperties properties;
    private final WeeklyRankingStore store;

    @Bean(JOB_NAME)
    public Job weeklyRankingJob(
        @Qualifier(FREEZE_STEP) Step freezeStep,
        @Qualifier(SCORE_STEP) Step scoreStep,
        @Qualifier(PUBLISH_STEP) Step publishStep
    ) {
        return new JobBuilder(JOB_NAME, jobRepository)
            .validator(new WeeklyRankingParametersValidator())
            .start(freezeStep)
            .next(scoreStep)
            .next(publishStep)
            .listener(jobListener)
            .build();
    }

    @Bean(FREEZE_STEP)
    public Step freezeStep(WeeklyRankingFreezer freezer) {
        return new StepBuilder(FREEZE_STEP, jobRepository)
            .tasklet(new FreezeInputTasklet(freezer), transactionManager)
            .listener(stepMonitorListener)
            .build();
    }

    @Bean(SCORE_STEP)
    public Step scoreStep(
        JdbcCursorItemReader<ProductScore> weeklyRankingProductReader,
        ScoreStageWriter scoreStageWriter
    ) {
        return new StepBuilder(SCORE_STEP, jobRepository)
            .<ProductScore, ProductScore>chunk(properties.chunkSize(), transactionManager)
            .reader(weeklyRankingProductReader)
            .writer(scoreStageWriter)
            .listener(stepMonitorListener)
            .build();
    }

    @Bean(PUBLISH_STEP)
    public Step publishStep(WeeklyRankingPublisher publisher) {
        return new StepBuilder(PUBLISH_STEP, jobRepository)
            .tasklet(new PublishRankingTasklet(publisher), transactionManager)
            .listener(stepMonitorListener)
            .build();
    }

    /**
     * 고정 입력(TB-20)을 상품별로 묶어 quantity 합까지 읽는다. 상품 하나가 item 하나이고 productId 오름차순이다 (DR-68).
     * 현재 활동 테이블(TB-13)은 읽지 않는다. 입력이 바뀌지 않으므로 재시작 때 같은 순서로 다시 읽고 read.count 만큼 건너뛴다.
     * step scope: 실행마다 새로 만들고 job parameter 를 실제 실행에서 받는다.
     */
    @Bean
    @StepScope
    public JdbcCursorItemReader<ProductScore> weeklyRankingProductReader(
        DataSource dataSource,
        @Value("#{jobParameters['" + WeeklyRankingTarget.PERIOD_START + "']}") String periodStart,
        @Value("#{jobParameters['" + WeeklyRankingTarget.REVISION + "']}") String revision
    ) {
        String snapshotId = WeeklyRankingTarget.of(periodStart, revision).snapshotId();
        return new JdbcCursorItemReaderBuilder<ProductScore>()
            .name(READER_NAME)
            .saveState(true)
            .dataSource(dataSource)
            .sql("select product_id, sum(quantity) as score from ranking_snapshot_input where snapshot_id = ? "
                + "group by product_id order by product_id")
            .preparedStatementSetter(ps -> ps.setString(1, snapshotId))
            .rowMapper((rs, rowNum) -> new ProductScore(rs.getLong("product_id"), rs.getLong("score")))
            .build();
    }

    @Bean
    @StepScope
    public ScoreStageWriter scoreStageWriter(
        @Value("#{jobParameters['" + WeeklyRankingTarget.PERIOD_START + "']}") String periodStart,
        @Value("#{jobParameters['" + WeeklyRankingTarget.REVISION + "']}") String revision
    ) {
        return new ScoreStageWriter(store, WeeklyRankingTarget.of(periodStart, revision).snapshotId());
    }
}
