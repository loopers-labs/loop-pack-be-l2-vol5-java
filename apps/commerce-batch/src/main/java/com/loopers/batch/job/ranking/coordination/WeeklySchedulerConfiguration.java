package com.loopers.batch.job.ranking.coordination;

import com.loopers.infrastructure.persistence.ranking.JdbcWeeklyRankingOrderPartitionInputs;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@Profile("ranking-scheduler")
class WeeklySchedulerConfiguration {
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock rankingSchedulerClock() {
        return Clock.systemUTC();
    }

    @Bean
    Settings rankingSchedulerSettings(Environment environment) {
        if (!environment.acceptsProfiles(Profiles.of("ranking-preserve"))
            || environment.getProperty("spring.batch.job.enabled", boolean.class, true)) {
            throw new IllegalStateException("랭킹 스케줄러는 ranking-preserve와 수동 Batch 실행 설정을 사용해야 합니다.");
        }
        return new Settings(environment.getProperty("ranking.scheduler.node-id", UUID.randomUUID().toString()),
            environment.getProperty("ranking.scheduler.local-grid", int.class, 2),
            environment.getProperty("ranking.scheduler.member-timeout", Duration.class, Duration.ofSeconds(30)),
            environment.getProperty("ranking.scheduler.heartbeat-delay", Duration.class, Duration.ofSeconds(5)));
    }

    @Bean
    WeeklyPlanStore weeklyPlanStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        return new WeeklyPlanStore(jdbc, manager);
    }

    @Bean
    WeeklyOrderPartitionExecutions weeklyOrderPartitionExecutions(@Qualifier("weeklyRankingOrderPartition") Job job, JobLauncher launcher, JobExplorer explorer,
        JobRepository jobs, JdbcTemplate jdbc, PlatformTransactionManager manager, JdbcWeeklyRankingOrderPartitionInputs inputs, Settings cfg) {
        return new WeeklyOrderPartitionExecutions(job, launcher, explorer, jobs, jdbc, manager,
            new OrderPartitionRestartEvidence(jdbc, jobs, inputs), cfg.timeout());
    }

    @Bean
    ApplicationRunner weeklyRankingCommand(Environment environment, WeeklyPlanStore plans, WeeklyOrderPartitionExecutions runs,
                                           Settings cfg, Clock clock) {
        return arguments -> {
            switch (environment.getProperty("ranking.scheduler.mode", "resident")) {
                case "resident" -> plans.register(cfg.node(), cfg.grid(), cfg.timeout());
                case "request" -> {
                    LocalDate period = LocalDate.parse(environment.getRequiredProperty("ranking.scheduler.request-period"));
                    if (period.isAfter(latestClosedWeek(clock))) {
                        throw new IllegalArgumentException("닫힌 주간 UTC 기간만 요청할 수 있습니다.");
                    }
                    int revision = environment.getRequiredProperty("ranking.scheduler.request-revision", Integer.class);
                    var snapshot = plans.ensurePlan(period, revision, cfg.timeout(), false);
                    LoggerFactory.getLogger(WeeklySchedulerConfiguration.class).info("랭킹 계획 요청 snapshot={}", snapshot);
                }
                case "recover" -> runs.recover(UUID.fromString(environment.getRequiredProperty("ranking.scheduler.snapshot-id")),
                    environment.getRequiredProperty("ranking.scheduler.order-partition-id", Integer.class),
                    environment.getRequiredProperty("ranking.scheduler.owner-id"),
                    environment.getRequiredProperty("ranking.scheduler.generation", Long.class),
                    environment.getRequiredProperty("ranking.scheduler.execution-id", Long.class),
                    environment.getProperty("ranking.scheduler.termination-confirmed", boolean.class, false),
                    environment.getRequiredProperty("ranking.scheduler.reason"));
                default -> throw new IllegalArgumentException("랭킹 스케줄러 모드는 resident, request, recover 중 하나여야 합니다.");
            }
        };
    }

    static LocalDate latestClosedWeek(Clock clock) {
        return clock.instant().minusSeconds(60).atZone(ZoneOffset.UTC).toLocalDate()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
    }

    record Settings(String node, int grid, Duration timeout, Duration heartbeat) {
        Settings {
            if (node == null || !node.matches("[a-zA-Z0-9._:-]{1,128}") || grid < 1 || timeout.compareTo(heartbeat) <= 0
                || heartbeat.isZero() || heartbeat.isNegative()) {
                throw new IllegalArgumentException("노드 식별자·로컬 동시성·heartbeat와 만료 간격을 확인하십시오.");
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @Profile("ranking-scheduler")
    @ConditionalOnProperty(name = "ranking.scheduler.mode", havingValue = "resident", matchIfMissing = true)
    static class Resident {
        private final Settings cfg;
        private final Clock clock;
        private final WeeklyPlanStore plans;
        private final WeeklyOrderPartitionExecutions runs;
        private final WeeklyPlanFinalizer finalizer;
        private final boolean autoPlan;

        Resident(Settings cfg, Clock clock, WeeklyPlanStore plans, WeeklyOrderPartitionExecutions runs,
                 WeeklyPlanFinalizer finalizer, Environment environment,
                 @Qualifier("weeklyRankingLocalProductPartitionExecutor") ThreadPoolTaskExecutor executor,
                 @Qualifier("mySqlMainDataSource") HikariDataSource source) {
            requireCapacity(executor.getMaxPoolSize(), source.getMaximumPoolSize());
            this.cfg = cfg;
            this.clock = clock;
            this.plans = plans;
            this.runs = runs;
            this.finalizer = finalizer;
            autoPlan = environment.getProperty("ranking.scheduler.auto-plan", boolean.class, true);
        }

        static void requireCapacity(int workers, int connections) {
            long minimum = 2L * workers + 3;
            if (connections < minimum) {
                throw new IllegalStateException("랭킹 worker " + workers + "개에는 커서·청크와 운영/감사용 커넥션이 최소 "
                    + minimum + "개 필요합니다. 현재 maximum-pool-size=" + connections);
            }
        }

        @Scheduled(fixedDelayString = "${ranking.scheduler.heartbeat-delay:PT5S}",
            initialDelayString = "${ranking.scheduler.heartbeat-delay:PT5S}")
        void heartbeat() {
            plans.heartbeat(cfg.node(), cfg.grid());
        }

        @Scheduled(fixedDelayString = "${ranking.scheduler.poll-delay:PT1S}", initialDelayString = "${ranking.scheduler.poll-delay:PT1S}")
        void work() {
            plans.claim(cfg.node(), cfg.timeout()).ifPresent(runs::run);
        }

        @Scheduled(fixedDelayString = "${ranking.scheduler.poll-delay:PT1S}", initialDelayString = "${ranking.scheduler.poll-delay:PT1S}")
        void maintain() {
            runs.reconcileAll();
            for (var snapshot : plans.publishable()) {
                try {
                    finalizer.finalizePlan(snapshot);
                } catch (RuntimeException failure) {
                    LoggerFactory.getLogger(Resident.class).error("랭킹 공개 재확인 필요 snapshot={}", snapshot, failure);
                }
            }
            if (autoPlan) {
                var latest = latestClosedWeek(clock);
                for (var due = plans.firstDue(latest); !due.isAfter(latest); due = due.plusWeeks(1)) {
                    try {
                        plans.ensurePlan(due, 1, cfg.timeout(), true);
                    } catch (IllegalStateException conflict) {
                        LoggerFactory.getLogger(Resident.class).error("주간 계획 생성 보류 period={}", due, conflict);
                    }
                }
            }
        }
    }
}
