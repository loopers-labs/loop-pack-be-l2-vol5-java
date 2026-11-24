package com.loopers.support.ranking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.CommerceBatchApplication;
import com.loopers.batch.job.weeklyranking.WeeklyRankingJobParameters;
import com.loopers.domain.ranking.WeeklyRankingKey;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Test entry point using only production dependencies in a fresh JVM on the caller's owned database. */
public final class WeeklyRankingProcessProbe {
    private static final List<Long> VISITED = new CopyOnWriteArrayList<>();
    private static Long failBeforeProduct;

    private WeeklyRankingProcessProbe() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2) {
            throw new IllegalArgumentException("Expected mode and result file");
        }
        Path resultFile = Path.of(arguments[1]);
        if (arguments[0].equals("initialize-schema")) {
            initializeMetadata();
            new ObjectMapper().writeValue(resultFile.toFile(), Map.of("status", "INITIALIZED"));
            return;
        }
        if (!arguments[0].equals("run")) {
            throw new IllegalArgumentException("Unknown owned Batch probe mode");
        }
        String failure = System.getenv("W9_OWNED_FAIL_PRODUCT");
        failBeforeProduct = failure == null || failure.isBlank() ? null : Long.valueOf(failure);
        WeeklyRankingKey key = new WeeklyRankingKey(LocalDate.parse(required("W9_OWNED_WEEK")),
            Integer.parseInt(required("W9_OWNED_REVISION")));
        int exitCode;
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
            CommerceBatchApplication.class, ProbeConfiguration.class).web(WebApplicationType.NONE)
            .run(configuration())) {
            JobExecution execution = context.getBean(JobLauncher.class).run(context.getBean("weeklyRanking", Job.class),
                WeeklyRankingJobParameters.parameters(key));
            Map<String, Object> result = describe(context, key, execution);
            context.getBean(ObjectMapper.class).writeValue(resultFile.toFile(), result);
            exitCode = execution.getStatus() == BatchStatus.COMPLETED ? 0
                : execution.getStatus() == BatchStatus.FAILED ? 2 : 3;
        }
        System.exit(exitCode);
    }

    private static void initializeMetadata() {
        try (HikariDataSource source = new HikariDataSource()) {
            source.setJdbcUrl(required("W9_OWNED_JDBC_URL"));
            source.setUsername(required("W9_OWNED_USERNAME"));
            source.setPassword(required("W9_OWNED_PASSWORD"));
            source.setMaximumPoolSize(2);
            source.setMinimumIdle(0);
            source.setConnectionTimeout(5000);
            new ResourceDatabasePopulator(new ClassPathResource("org/springframework/batch/core/schema-mysql.sql"))
                .execute(source);
        }
    }

    private static String[] configuration() {
        // CLI properties override inherited environment/local profiles, and classpath-only config prevents user files.
        return new String[]{
            "--spring.config.location=classpath:/application.yml", "--spring.config.additional-location=",
            "--spring.profiles.active=test,weekly-ranking", "--spring.main.banner-mode=off",
            "--spring.main.web-application-type=none", "--spring.batch.job.enabled=false",
            "--spring.batch.job.name=weeklyRanking", "--spring.batch.jdbc.initialize-schema=never",
            "--spring.jpa.hibernate.ddl-auto=none", "--spring.jpa.show-sql=false", "--spring.sql.init.mode=never",
            "--spring.lifecycle.timeout-per-shutdown-phase=5s", "--commerce.ranking.chunk-size=2",
            "--commerce.ranking.snapshot.page-size=2", "--management.health.defaults.enabled=false",
            "--w9.owned-probe.enabled=true",
            "--datasource.mysql-jpa.main.jdbc-url=" + required("W9_OWNED_JDBC_URL"),
            "--datasource.mysql-jpa.main.username=" + required("W9_OWNED_USERNAME"),
            "--datasource.mysql-jpa.main.password=" + required("W9_OWNED_PASSWORD"),
            "--datasource.mysql-jpa.main.minimum-idle=0", "--datasource.mysql-jpa.main.maximum-pool-size=5",
            "--datasource.mysql-jpa.main.connection-timeout=5000",
            "--datasource.mysql-jpa.main.data-source-properties.connectTimeout=5000",
            "--datasource.mysql-jpa.main.data-source-properties.socketTimeout=10000",
            "--datasource.redis.database=0", "--datasource.redis.master.host=127.0.0.1",
            "--datasource.redis.master.port=1", "--datasource.redis.replicas[0].host=127.0.0.1",
            "--datasource.redis.replicas[0].port=1", "--logging.level.root=WARN"
        };
    }

    private static Map<String, Object> describe(ConfigurableApplicationContext context, WeeklyRankingKey key,
                                                JobExecution execution) {
        JobExplorer explorer = context.getBean(JobExplorer.class);
        JobExecution persisted = explorer.getJobExecution(execution.getId());
        if (persisted == null) {
            throw new IllegalStateException("Missing persisted Batch execution");
        }
        JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
        var manifest = jdbc.queryForMap("SELECT input_checksum, result_checksum FROM weekly_ranking_snapshot WHERE snapshot_id=?",
            key.snapshotId());
        long position = -1;
        for (StepExecution step : execution.getStepExecutions()) {
            if (step.getStepName().equals("calculateWeeklyScores")) {
                StepExecution stored = explorer.getStepExecution(execution.getId(), step.getId());
                if (stored == null) {
                    throw new IllegalStateException("Missing persisted score Step");
                }
                position = stored.getExecutionContext().getInt("weeklyRankingProducts.read.count", -1);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobInstanceId", persisted.getJobInstance().getInstanceId());
        result.put("jobExecutionId", persisted.getId());
        result.put("status", persisted.getStatus().name());
        result.put("snapshotId", key.snapshotId());
        result.put("stepNames", execution.getStepExecutions().stream().map(StepExecution::getStepName).toList());
        result.put("readerPosition", position);
        result.put("visitedProducts", new ArrayList<>(VISITED));
        result.put("inputChecksum", manifest.get("input_checksum"));
        result.put("resultChecksum", manifest.get("result_checksum"));
        result.put("failureTypes", execution.getAllFailureExceptions().stream().map(failure -> failure.getClass().getName()).toList());
        return result;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing owned probe setting " + name);
        }
        return value;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "w9.owned-probe.enabled", havingValue = "true")
    public static class ProbeConfiguration {
        @Bean
        static BeanPostProcessor recordAndOptionallyInterruptProductionProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!name.equals("scopedTarget.weeklyRankingProcessor")) {
                        return bean;
                    }
                    return Proxy.newProxyInstance(ItemProcessor.class.getClassLoader(), new Class<?>[]{ItemProcessor.class},
                        (proxy, method, arguments) -> {
                            if (method.getName().equals("process") && arguments[0] instanceof Long productId) {
                                VISITED.add(productId);
                                if (productId.equals(failBeforeProduct)) {
                                    throw new InjectedBeforeProductFailure(productId);
                                }
                            }
                            try {
                                return method.invoke(bean, arguments);
                            } catch (InvocationTargetException exception) {
                                throw exception.getCause();
                            }
                        });
                }
            };
        }
    }

    public static final class InjectedBeforeProductFailure extends RuntimeException {
        public InjectedBeforeProductFailure(long productId) {
            super("Injected before product " + productId + " after previous chunk commits");
        }
    }
}
