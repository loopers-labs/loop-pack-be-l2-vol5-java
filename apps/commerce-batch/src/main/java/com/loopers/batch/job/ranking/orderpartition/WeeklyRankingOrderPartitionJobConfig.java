package com.loopers.batch.job.ranking.orderpartition;

import com.loopers.application.ranking.command.WeeklyRankingOrderPartitionCommand;
import com.loopers.application.ranking.service.WriteWeeklyRankingPartialScoreService;
import com.loopers.domain.ranking.model.vo.RankingPeriod;
import com.loopers.domain.ranking.model.vo.WeeklyPurchaseScore;
import com.loopers.infrastructure.persistence.ranking.JdbcWeeklyRankingOrderPartitionInputs;
import com.loopers.infrastructure.persistence.ranking.JdbcWeeklyRankingOrderPartitionInputs.OrderPartitionBounds;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ExecutionContext;
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
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = "weeklyRankingOrderPartition")
public class WeeklyRankingOrderPartitionJobConfig {
    private static final String FREEZE = "freezeWeeklyRankingOrderPartition";
    private static final String MANAGER = "calculateWeeklyRankingOrderPartition";
    private static final String WORKER = "scoreWeeklyRankingLocalProductPartition";
    private static final Set<String> IDENTITY = Set.of("snapshotId", "orderPartitionId");
    private static final Set<String> ALL_PARAMETERS = Set.of("snapshotId", "orderPartitionId", "periodStart", "revision", "ownerId", "generation");

    @Bean("weeklyRankingOrderPartition")
    public Job weeklyRankingOrderPartition(JobRepository repository, @Qualifier(FREEZE) Step freeze, @Qualifier(MANAGER) Step manager,
                                  JdbcWeeklyRankingOrderPartitionInputs inputs) {
        return new JobBuilder("weeklyRankingOrderPartition", repository)
            .validator(parameters -> {
                try {
                    command(parameters);
                } catch (RuntimeException ex) {
                    throw new JobParametersInvalidException("주문 파티션 Job parameter가 유효하지 않습니다: " + ex.getMessage());
                }
            })
            .listener(new JobExecutionListener() {
                @Override
                public void beforeJob(JobExecution execution) {
                    inputs.bindExecution(command(execution.getJobParameters()), execution.getId());
                }
            }).start(freeze).next(manager).build();
    }

    @Bean(FREEZE)
    public Step freezeWeeklyRankingOrderPartition(JobRepository repository, PlatformTransactionManager transactions,
                                          JdbcWeeklyRankingOrderPartitionInputs inputs) {
        return new StepBuilder(FREEZE, repository).tasklet((contribution, context) -> {
            inputs.freeze(command(contribution.getStepExecution().getJobParameters()));
            return RepeatStatus.FINISHED;
        }, transactions).build();
    }

    @Bean("weeklyRankingLocalProductPartitionExecutor")
    public ThreadPoolTaskExecutor weeklyRankingLocalProductPartitionExecutor(@Value("${ranking.weekly.local-workers:2}") int workers) {
        if (workers < 1) {
            throw new IllegalArgumentException("로컬 worker 수는 양수여야 합니다.");
        }
        var executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("weekly-ranking-local-product-partition-");
        executor.setCorePoolSize(workers);
        executor.setMaxPoolSize(workers);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        return executor;
    }

    @Bean(MANAGER)
    public Step calculateWeeklyRankingOrderPartition(JobRepository repository, @Qualifier(WORKER) Step worker,
                                           @Qualifier("weeklyRankingLocalProductPartitioner") Partitioner localProductPartitioner,
                                           @Qualifier("weeklyRankingLocalProductPartitionExecutor") ThreadPoolTaskExecutor executor) {
        return new StepBuilder(MANAGER, repository).partitioner(WORKER, localProductPartitioner).step(worker).taskExecutor(executor).build();
    }

    @Bean("weeklyRankingLocalProductPartitioner")
    @StepScope
    public Partitioner weeklyRankingLocalProductPartitioner(JdbcWeeklyRankingOrderPartitionInputs inputs,
        @Value("#{stepExecution.jobExecution.jobParameters}") JobParameters parameters) {
        var command = command(parameters);
        return ignored -> {
            var result = new LinkedHashMap<String, ExecutionContext>();
            for (var localProductPartition : inputs.localProductPartitions(command)) {
                var context = new ExecutionContext();
                context.putInt("localProductPartitionIndex", localProductPartition.index());
                context.putLong("minProductId", localProductPartition.firstProduct());
                context.putLong("maxProductId", localProductPartition.lastProduct());
                context.put("minOrderId", localProductPartition.orderPartitionBounds().min());
                context.put("maxOrderId", localProductPartition.orderPartitionBounds().max());
                result.put("localProductPartition-" + localProductPartition.index(), context);
            }
            return result;
        };
    }

    @Bean(WORKER)
    public Step scoreWeeklyRankingLocalProductPartition(JobRepository repository, PlatformTransactionManager transactions,
        @Qualifier("localProductPartitionReader") ItemStreamReader<Long> reader,
        @Qualifier("localProductPartitionProcessor") ItemProcessor<Long, WeeklyPurchaseScore> processor,
        @Qualifier("localProductPartitionWriter") ItemWriter<WeeklyPurchaseScore> writer,
        @Value("${ranking.weekly.chunk-size:100}") int chunkSize) {
        return new StepBuilder(WORKER, repository).<Long, WeeklyPurchaseScore>chunk(chunkSize, transactions)
            .reader(reader).processor(processor).writer(writer).build();
    }

    @Bean("localProductPartitionReader")
    @StepScope
    public ItemStreamReader<Long> localProductPartitionReader(DataSource source, @Value("#{jobParameters['snapshotId']}") String snapshot,
        @Value("#{stepExecutionContext['minProductId']}") long first, @Value("#{stepExecutionContext['maxProductId']}") long last,
        @Value("#{stepExecutionContext['minOrderId']}") Long minOrder, @Value("#{stepExecutionContext['maxOrderId']}") Long maxOrder) {
        String sql = """
            SELECT DISTINCT product_id FROM weekly_ranking_snapshot_inputs WHERE snapshot_id=? AND product_id BETWEEN ? AND ?
            """ + (minOrder == null ? "" : " AND order_id>=?") + (maxOrder == null ? "" : " AND order_id<?") + " ORDER BY product_id";
        return new JdbcCursorItemReaderBuilder<Long>().name("weeklyRankingLocalProductPartitionProducts").saveState(true).dataSource(source)
            .sql(sql).preparedStatementSetter(statement -> {
                statement.setString(1, snapshot);
                statement.setLong(2, first);
                statement.setLong(3, last);
                int orderParameter = 4;
                if (minOrder != null) {
                    statement.setLong(orderParameter++, minOrder);
                }
                if (maxOrder != null) {
                    statement.setLong(orderParameter, maxOrder);
                }
            }).rowMapper((row, index) -> row.getLong("product_id")).fetchSize(Integer.MIN_VALUE)
            .verifyCursorPosition(false).build();
    }

    @Bean("localProductPartitionProcessor")
    @StepScope
    public ItemProcessor<Long, WeeklyPurchaseScore> localProductPartitionProcessor(JdbcWeeklyRankingOrderPartitionInputs inputs,
        @Value("#{jobParameters['snapshotId']}") String snapshot, @Value("#{stepExecutionContext['minOrderId']}") Long minOrder,
        @Value("#{stepExecutionContext['maxOrderId']}") Long maxOrder) {
        var id = UUID.fromString(snapshot);
        var bounds = new OrderPartitionBounds(minOrder, maxOrder);
        return product -> inputs.calculate(id, product, bounds);
    }

    @Bean("localProductPartitionWriter")
    @StepScope
    public ItemWriter<WeeklyPurchaseScore> localProductPartitionWriter(WriteWeeklyRankingPartialScoreService scores,
        @Value("#{stepExecution.jobExecution.jobParameters}") JobParameters parameters) {
        var run = command(parameters);
        return chunk -> scores.write(run.snapshotId(), run.orderPartitionId(), run.ownerId(), run.generation(), chunk.getItems());
    }

    private static WeeklyRankingOrderPartitionCommand command(JobParameters parameters) {
        if (parameters == null || !parameters.getParameters().keySet().equals(ALL_PARAMETERS)
            || !parameters.getIdentifyingParameters().keySet().equals(IDENTITY)) {
            throw new IllegalArgumentException("snapshotId와 orderPartitionId를 식별값으로 사용하는 여섯 parameter가 필요합니다.");
        }
        String text = parameters.getString("snapshotId");
        var snapshot = UUID.fromString(text);
        if (!snapshot.toString().equals(text)) {
            throw new IllegalArgumentException("정규 UUID 형식이 필요합니다.");
        }
        return new WeeklyRankingOrderPartitionCommand(snapshot, Math.toIntExact(parameters.getLong("orderPartitionId")),
            new RankingPeriod(LocalDate.parse(parameters.getString("periodStart"))), Math.toIntExact(parameters.getLong("revision")),
            parameters.getString("ownerId"), parameters.getLong("generation"));
    }
}
