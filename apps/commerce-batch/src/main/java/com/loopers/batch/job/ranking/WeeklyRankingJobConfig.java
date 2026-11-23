package com.loopers.batch.job.ranking;

import com.loopers.batch.job.ranking.step.ClearRanksListener;
import com.loopers.batch.job.ranking.step.FreezeWeeklyScoresTasklet;
import com.loopers.batch.job.ranking.step.FrozenScore;
import com.loopers.batch.job.ranking.step.FrozenScoreReader;
import com.loopers.batch.job.ranking.step.PublishWeeklyRankingTasklet;
import com.loopers.batch.job.ranking.step.ScoreRankProcessor;
import com.loopers.batch.job.ranking.step.ScoreRankWriter;
import com.loopers.batch.listener.JobListener;
import com.loopers.batch.listener.StepMonitorListener;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;

import javax.sql.DataSource;
import java.util.Map;

/**
 * 주간 랭킹 Job. 점수 고정, 순위 매기기, 공개의 세 Step이다. {@code spring.batch.job.name}이 weeklyRanking일 때만 등록한다.
 * 식별 파라미터는 periodStart, revision 두 문자열이다. RunIdIncrementer나 실행 시각 파라미터를 쓰지 않는다.
 * 다시 실행하면 완료한 Step은 건너뛰고, 순위 Step은 처음부터 다시 매긴다.
 */
@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = WeeklyRankingJobConfig.JOB_NAME)
@Configuration
public class WeeklyRankingJobConfig {

    public static final String JOB_NAME = "weeklyRanking";
    public static final String FREEZE_STEP = "freezeWeeklyScores";
    public static final String RANK_STEP = "rankWeeklyScores";
    public static final String PUBLISH_STEP = "publishWeeklyRanking";

    // 순위 Step의 chunk 크기이자 Reader의 페이지 크기. 잡 파라미터가 아니라서 JobInstance 식별에 영향이 없다.
    private final int chunkSize;

    public WeeklyRankingJobConfig(@Value("${weekly-ranking.chunk-size:100}") int chunkSize) {
        if (chunkSize < 1) {
            throw new IllegalArgumentException("weekly-ranking.chunk-size는 1 이상이어야 한다. chunkSize=" + chunkSize);
        }
        this.chunkSize = chunkSize;
    }

    @Bean(JOB_NAME)
    public Job weeklyRanking(
        JobRepository jobRepository,
        @Qualifier(FREEZE_STEP) Step freezeWeeklyScores,
        @Qualifier(RANK_STEP) Step rankWeeklyScores,
        @Qualifier(PUBLISH_STEP) Step publishWeeklyRanking,
        WeeklyRankingParametersValidator parametersValidator,
        JobListener jobListener
    ) {
        return new JobBuilder(JOB_NAME, jobRepository)
            .validator(parametersValidator)
            .start(freezeWeeklyScores)
            .next(rankWeeklyScores)
            .next(publishWeeklyRanking)
            .listener(jobListener)
            .build();
    }

    /**
     * 원본을 잠그지 않도록 Step의 트랜잭션 속성으로 READ_COMMITTED를 준다.
     * Tasklet에 @Transactional을 붙이면 이미 열린 Step 트랜잭션에 합류해 격리 수준이 무시된다.
     * 롤백 규칙은 Tasklet Step 기본값처럼 모든 예외로 둔다. 일반 DefaultTransactionAttribute는 RuntimeException에서만 롤백해,
     * checked 예외가 나면 점수만 커밋하고 Tasklet을 다시 부른다.
     */
    @Bean(FREEZE_STEP)
    public Step freezeWeeklyScores(
        JobRepository jobRepository,
        PlatformTransactionManager transactionManager,
        FreezeWeeklyScoresTasklet freezeWeeklyScoresTasklet,
        StepMonitorListener stepMonitorListener
    ) {
        DefaultTransactionAttribute readCommitted = new DefaultTransactionAttribute() {
            @Override
            public boolean rollbackOn(Throwable ex) {
                return true;
            }
        };
        readCommitted.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return new StepBuilder(FREEZE_STEP, jobRepository)
            .tasklet(freezeWeeklyScoresTasklet, transactionManager)
            .transactionAttribute(readCommitted)
            .listener(stepMonitorListener)
            .build();
    }

    @Bean(RANK_STEP)
    public Step rankWeeklyScores(
        JobRepository jobRepository,
        PlatformTransactionManager transactionManager,
        JdbcPagingItemReader<FrozenScore> frozenScoreReader,
        ScoreRankProcessor scoreRankProcessor,
        ScoreRankWriter scoreRankWriter,
        ClearRanksListener clearRanksListener,
        StepMonitorListener stepMonitorListener
    ) {
        return new StepBuilder(RANK_STEP, jobRepository)
            .<FrozenScore, ProductRank>chunk(chunkSize, transactionManager)
            .reader(frozenScoreReader)
            .processor(scoreRankProcessor)
            .writer(scoreRankWriter)
            .listener(clearRanksListener)
            .listener(stepMonitorListener)
            .build();
    }

    // 반환 타입을 구현 클래스로 둬야 Step이 이 Reader를 ItemStream으로 알아보고 열고 닫는다.
    @Bean
    @StepScope
    public JdbcPagingItemReader<FrozenScore> frozenScoreReader(
        DataSource dataSource,
        @Value("#{jobParameters}") Map<String, Object> jobParameters
    ) {
        return FrozenScoreReader.create(dataSource, WeeklyRankingKey.from(jobParameters), chunkSize);
    }

    @Bean
    @StepScope
    public ScoreRankWriter scoreRankWriter(
        WeeklyRankingRepository repository,
        @Value("#{jobParameters}") Map<String, Object> jobParameters
    ) {
        return new ScoreRankWriter(repository, WeeklyRankingKey.from(jobParameters));
    }

    @Bean(PUBLISH_STEP)
    public Step publishWeeklyRanking(
        JobRepository jobRepository,
        PlatformTransactionManager transactionManager,
        PublishWeeklyRankingTasklet publishWeeklyRankingTasklet,
        StepMonitorListener stepMonitorListener
    ) {
        return new StepBuilder(PUBLISH_STEP, jobRepository)
            .tasklet(publishWeeklyRankingTasklet, transactionManager)
            .listener(stepMonitorListener)
            .build();
    }
}
