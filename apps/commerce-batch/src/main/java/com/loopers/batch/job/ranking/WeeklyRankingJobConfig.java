package com.loopers.batch.job.ranking;

import com.loopers.application.ranking.service.CalculateWeeklyScoreService;
import com.loopers.application.ranking.command.PublishWeeklyRankingCommand;
import com.loopers.application.ranking.result.WeeklyRankingPublicationResult;
import com.loopers.application.ranking.service.PublishWeeklyRankingService;
import com.loopers.application.ranking.service.FreezeRankingSnapshotService;
import com.loopers.application.ranking.service.ValidateFrozenRankingSnapshotService;
import com.loopers.domain.ranking.model.vo.WeeklyPurchaseScore;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = "weeklyRanking")
public class WeeklyRankingJobConfig {
    @Bean("weeklyRanking")
    public Job weeklyRanking(JobRepository repository, @Qualifier("freezeWeeklyRankingInput") Step freeze,
                             @Qualifier("calculateWeeklyScores") Step calculation,
                             @Qualifier("publishWeeklyRanking") Step publication) {
        return new JobBuilder("weeklyRanking", repository).validator(new WeeklyRankingParametersValidator())
            .start(freeze).next(calculation).next(publication).build();
    }

    @Bean("publishWeeklyRanking")
    public Step publishWeeklyRanking(JobRepository repository, PlatformTransactionManager manager,
        @Qualifier("weeklyRankingPublicationTasklet") Tasklet tasklet) {
        return new StepBuilder("publishWeeklyRanking", repository).tasklet(tasklet, manager)
            .listener(new StepExecutionListener() {
                @Override
                public ExitStatus afterStep(StepExecution execution) {
                    return execution.getStatus() == BatchStatus.COMPLETED && WeeklyRankingPublicationResult.SUPERSEDED.name()
                        .equals(execution.getExecutionContext().getString("weeklyRanking.publicationOutcome", ""))
                        ? new ExitStatus("SUPERSEDED") : null;
                }
            }).build();
    }

    @Bean("weeklyRankingPublicationTasklet")
    @StepScope
    public Tasklet weeklyRankingPublicationTasklet(PublishWeeklyRankingService publisher,
        @Value("#{stepExecution.jobExecution.jobParameters}") JobParameters parameters) {
        return (contribution, context) -> {
            var command = WeeklyRankingParametersValidator.command(parameters);
            var outcome = publisher.publish(new PublishWeeklyRankingCommand(command.snapshotId(), command.periodStart(),
                command.revision()));
            contribution.getStepExecution().getExecutionContext().putString("weeklyRanking.publicationOutcome", outcome.name());
            return RepeatStatus.FINISHED;
        };
    }

    @Bean("freezeWeeklyRankingInput")
    public Step freezeWeeklyRankingInput(JobRepository repository, PlatformTransactionManager manager,
                                        @Qualifier("weeklyRankingFreezeTasklet") Tasklet tasklet) {
        return new StepBuilder("freezeWeeklyRankingInput", repository).tasklet(tasklet, manager).build();
    }

    @Bean("weeklyRankingFreezeTasklet")
    @StepScope
    public Tasklet weeklyRankingFreezeTasklet(FreezeRankingSnapshotService freezer,
        @Value("#{stepExecution.jobExecution.jobParameters}") JobParameters parameters) {
        return (contribution, context) -> {
            freezer.freeze(WeeklyRankingParametersValidator.command(parameters));
            return RepeatStatus.FINISHED;
        };
    }

    @Bean("calculateWeeklyScores")
    public Step calculateWeeklyScores(JobRepository repository, PlatformTransactionManager manager,
        @Qualifier("weeklyRankingReader") ItemStreamReader<Long> reader,
        @Qualifier("weeklyRankingProcessor") ItemProcessor<Long, WeeklyPurchaseScore> processor,
        @Qualifier("weeklyRankingWriter") ItemWriter<WeeklyPurchaseScore> writer,
        @Value("${ranking.weekly.chunk-size:100}") int chunkSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("주간 계산 chunk 크기는 양수여야 합니다.");
        }
        return new StepBuilder("calculateWeeklyScores", repository).<Long, WeeklyPurchaseScore>chunk(chunkSize, manager)
            .reader(reader).processor(processor).writer(writer).build();
    }

    @Bean("weeklyRankingReader")
    @StepScope
    public ItemStreamReader<Long> weeklyRankingReader(DataSource source, ValidateFrozenRankingSnapshotService validator,
        @Value("#{stepExecution.jobExecution.jobParameters}") JobParameters parameters) {
        var command = WeeklyRankingParametersValidator.command(parameters);
        validator.validate(command);
        return new JdbcCursorItemReaderBuilder<Long>().name("weeklyRankingProducts").saveState(true).dataSource(source)
            .sql("SELECT DISTINCT product_id FROM weekly_ranking_snapshot_inputs WHERE snapshot_id=? ORDER BY product_id ASC")
            .preparedStatementSetter(statement -> statement.setString(1, command.snapshotId().toString()))
            // MySQL streaming ResultSet does not support getRow(); this mapper never advances the cursor.
            .rowMapper((row, index) -> row.getLong("product_id")).fetchSize(Integer.MIN_VALUE)
            .verifyCursorPosition(false).build();
    }

    @Bean("weeklyRankingProcessor")
    @StepScope
    public ItemProcessor<Long, WeeklyPurchaseScore> weeklyRankingProcessor(CalculateWeeklyScoreService calculator,
        @Value("#{jobParameters['snapshotId']}") String snapshotId) {
        var snapshot = UUID.fromString(snapshotId);
        return product -> calculator.calculate(snapshot, product);
    }

    @Bean("weeklyRankingWriter")
    @StepScope
    public ItemWriter<WeeklyPurchaseScore> weeklyRankingWriter(CalculateWeeklyScoreService calculator) {
        return chunk -> calculator.write(chunk.getItems());
    }
}
