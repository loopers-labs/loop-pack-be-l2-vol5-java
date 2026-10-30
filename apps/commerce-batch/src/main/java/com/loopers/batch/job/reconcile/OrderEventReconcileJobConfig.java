package com.loopers.batch.job.reconcile;

import com.loopers.batch.listener.JobListener;
import com.loopers.batch.listener.StepMonitorListener;
import com.loopers.confg.kafka.KafkaConfig;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.batch.item.database.support.MySqlPagingQueryProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.transaction.PlatformTransactionManager;

// 실행마다 Job 한 번. 주기는 바깥 스케줄러가 정하고 "지금 바로 보기"도 같은 Job 실행이다(설계 27절).
@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = OrderEventReconcileJobConfig.JOB_NAME)
@Configuration
public class OrderEventReconcileJobConfig {
    public static final String JOB_NAME = "orderEventReconcileJob";
    private static final String STEP_NAME = "orderEventReconcileStep";

    @Bean(JOB_NAME)
    public Job orderEventReconcileJob(JobRepository jobRepository, Step orderEventReconcileStep, JobListener jobListener) {
        return new JobBuilder(JOB_NAME, jobRepository)
            .incrementer(new RunIdIncrementer())
            .start(orderEventReconcileStep)
            .listener(jobListener)
            .build();
    }

    @JobScope
    @Bean(STEP_NAME)
    public Step orderEventReconcileStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
        ReconcileProperties reconcileProperties, JdbcPagingItemReader<MissingOrder> missingOrderReader,
        RepublishRequestWriter republishRequestWriter, ReconcileStepListener reconcileStepListener,
        StepMonitorListener stepMonitorListener) {
        return new StepBuilder(STEP_NAME, jobRepository)
            .<MissingOrder, MissingOrder>chunk(reconcileProperties.chunkSize(), transactionManager)
            .reader(missingOrderReader)
            .writer(republishRequestWriter)
            .listener(reconcileStepListener)
            .listener(stepMonitorListener)
            .build();
    }

    @StepScope
    @Bean
    public ReconcileRun reconcileRun(ReconcileCheckpointRepository reconcileCheckpointRepository,
        ReconcileProperties reconcileProperties, Clock reconcileClock, NamedParameterJdbcTemplate namedParameterJdbcTemplate) {
        Optional<ReconcileCheckpoint> before = reconcileCheckpointRepository.find();
        ReconcileWindow window = ReconcileWindow.of(before, reconcileProperties.overlap(), reconcileProperties.grace(),
            reconcileClock.instant());
        Long confirmed = namedParameterJdbcTemplate.queryForObject(ReconcileQueries.COUNT_CONFIRMED, windowParameters(window),
            Long.class);
        return new ReconcileRun(window, before, confirmed == null ? 0 : confirmed);
    }

    @StepScope
    @Bean
    public JdbcPagingItemReader<MissingOrder> missingOrderReader(DataSource dataSource, ReconcileRun reconcileRun,
        ReconcileProperties reconcileProperties) {
        MySqlPagingQueryProvider queryProvider = new MySqlPagingQueryProvider();
        queryProvider.setSelectClause("o.id, o.confirmed_at");
        queryProvider.setFromClause("orders o");
        queryProvider.setWhereClause(ReconcileQueries.MISSING_CONDITION);
        Map<String, Order> sortKeys = new LinkedHashMap<String, Order>();
        sortKeys.put("confirmed_at", Order.ASCENDING);
        sortKeys.put("id", Order.ASCENDING);
        queryProvider.setSortKeys(sortKeys);
        // 재시작 위치는 reader 상태가 아니라 워터마크가 정한다.
        return new JdbcPagingItemReaderBuilder<MissingOrder>()
            .name("missingOrderReader")
            .dataSource(dataSource)
            .queryProvider(queryProvider)
            .parameterValues(windowParameters(reconcileRun.window()))
            .pageSize(reconcileProperties.chunkSize())
            .saveState(false)
            .rowMapper((rs, rowNum) -> new MissingOrder(rs.getLong("id"),
                rs.getObject("confirmed_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)))
            .build();
    }

    @StepScope
    @Bean
    public RepublishRequestWriter republishRequestWriter(KafkaTemplate<String, String> republishCommandKafkaTemplate,
        @Value("${commerce.kafka.topics.order-event-republish-requests}") String topic,
        ReconcileCheckpointRepository reconcileCheckpointRepository, ReconcileRun reconcileRun, Clock reconcileClock,
        ReconcileProperties reconcileProperties) {
        return new RepublishRequestWriter(republishCommandKafkaTemplate, topic, reconcileCheckpointRepository, reconcileRun,
            reconcileClock, reconcileProperties.sendTimeout());
    }

    @Bean
    public ReconcileStepListener reconcileStepListener(ReconcileRun reconcileRun,
        ReconcileCheckpointRepository reconcileCheckpointRepository, Clock reconcileClock) {
        return new ReconcileStepListener(reconcileRun, reconcileCheckpointRepository, reconcileClock);
    }

    @Bean
    public ReconcileCheckpointRepository reconcileCheckpointRepository(JdbcTemplate jdbcTemplate) {
        return new ReconcileCheckpointRepository(jdbcTemplate);
    }

    @Bean
    public Clock reconcileClock() {
        return Clock.systemUTC();
    }

    // 명령은 이미 만든 JSON 문자열이라 공통 producer(JsonSerializer)와 분리한다. 같은 명령이 두 번 쓰이지 않게 idempotence를 켠다.
    // factory를 Bean으로 두어 컨텍스트 종료 때 Spring이 producer를 닫게 한다.
    @Bean
    public ProducerFactory<String, String> republishCommandProducerFactory(KafkaProperties kafkaProperties) {
        return new DefaultKafkaProducerFactory<>(KafkaConfig.stringProducerProperties(kafkaProperties));
    }

    @Bean
    public KafkaTemplate<String, String> republishCommandKafkaTemplate(
        ProducerFactory<String, String> republishCommandProducerFactory) {
        return new KafkaTemplate<>(republishCommandProducerFactory);
    }

    private static Map<String, Object> windowParameters(ReconcileWindow window) {
        return Map.of(
            "fromAt", ReconcileCheckpointRepository.utc(window.fromAt()),
            "fromOrderId", window.fromOrderId(),
            "toAt", ReconcileCheckpointRepository.utc(window.toAt()));
    }
}
