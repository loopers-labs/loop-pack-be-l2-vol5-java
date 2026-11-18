package com.loopers.batch.job.ranking;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import com.sun.management.GarbageCollectionNotificationInfo;

@Tag("experiment")
@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME
})
@Sql("/sql/purchase-activity.sql")
class FreezeSnapshotMemoryExperimentTest {

    private static final List<Integer> SIZES = Arrays.stream(
        System.getProperty("exp029.sizes", "100000,200000,400000").split(",")
    ).map(String::trim).map(Integer::valueOf).toList();
    private static final int REPEATS = Integer.getInteger("exp029.repeats", 3);
    private static final long GROWTH_LIMIT_MB = 32;
    private static final String SNAPSHOT_ID = "weekly-20260817-r1";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private JobExplorer jobExplorer;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    @Qualifier(WeeklyRankingJobConfig.JOB_NAME)
    private Job job;

    private JdbcTemplate jdbc;

    private final List<Long> heapAfterGc = new CopyOnWriteArrayList<>();

    private final List<NotificationEmitter> emitters = new ArrayList<>();

    private NotificationListener listener;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        Set<String> heapPools = ManagementFactory.getMemoryPoolMXBeans().stream()
            .filter(pool -> pool.getType() == MemoryType.HEAP)
            .map(MemoryPoolMXBean::getName)
            .collect(Collectors.toSet());
        listener = (notification, handback) -> {
            if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType())) {
                return;
            }
            GarbageCollectionNotificationInfo info =
                GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
            heapAfterGc.add(info.getGcInfo().getMemoryUsageAfterGc().entrySet().stream()
                .filter(pool -> heapPools.contains(pool.getKey()))
                .mapToLong(pool -> pool.getValue().getUsed())
                .sum());
        };
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            NotificationEmitter emitter = (NotificationEmitter) collector;
            emitter.addNotificationListener(listener, null, null);
            emitters.add(emitter);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        for (NotificationEmitter emitter : emitters) {
            emitter.removeNotificationListener(listener);
        }
        truncateAll();
    }

    @DisplayName("EXP-029: 입력 고정이 붙잡는 heap 은 입력 행 수에 비례하지 않는다.")
    @Test
    void retainedHeapDoesNotGrowWithInputRows() throws Exception {
        List<String> report = new ArrayList<>();
        long largestMedianGrowthMb = 0;
        for (int rows : SIZES) {
            List<Long> growths = new ArrayList<>();
            for (int attempt = 1; attempt <= REPEATS; attempt++) {
                Measurement measurement = measure(rows);
                growths.add(measurement.growthMb());
                report.add("EXP-029 rows=%d attempt=%d %s".formatted(rows, attempt, measurement));
            }
            long median = growths.stream().sorted().toList().get(growths.size() / 2);
            report.add("EXP-029 rows=%d medianGrowthMb=%d".formatted(rows, median));
            if (rows == SIZES.get(SIZES.size() - 1)) {
                largestMedianGrowthMb = median;
            }
        }
        report.forEach(System.out::println);

        assertThat(largestMedianGrowthMb).isLessThan(GROWTH_LIMIT_MB);
    }

    private Measurement measure(int rows) throws Exception {
        truncateAll();
        seed(rows);

        System.gc();
        Thread.sleep(500);
        long baseline = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        heapAfterGc.clear();
        long startedAt = System.nanoTime();

        JobExecution execution = jobLauncher.run(job, new JobParametersBuilder()
            .addLocalDate("periodStart", LocalDate.of(2026, 8, 17))
            .addLong("revision", 1L)
            .addString("snapshotId", SNAPSHOT_ID)
            .toJobParameters());

        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
        Thread.sleep(500);
        List<Long> observed = List.copyOf(heapAfterGc);
        long peakAfterGc = observed.stream().mapToLong(Long::longValue).max().orElse(baseline);
        BatchStatus status = jobExplorer.getJobExecution(execution.getId()).getStatus();
        List<String> failures = execution.getAllFailureExceptions().stream().map(Throwable::toString).toList();
        new JobRepositoryTestUtils(jobRepository).removeJobExecutions(
            List.of(jobExplorer.getJobExecution(execution.getId()))
        );

        assertThat(status).as("job failed: %s", failures).isEqualTo(BatchStatus.COMPLETED);
        return new Measurement(baseline >> 20, peakAfterGc >> 20, observed.size(), elapsed.toMillis());
    }

    private void seed(int rows) {
        jdbc.execute((ConnectionCallback<Integer>) con -> {
            try (var session = con.createStatement()) {
                session.execute("set session cte_max_recursion_depth = " + rows);
            }
            try (var seed = con.prepareStatement("""
                insert into purchase_activity
                  (created_at, updated_at, order_id, product_id, quantity, confirmed_at)
                with recursive seq (n) as (
                  select 1 union all select n + 1 from seq where n < ?
                )
                select now(6), now(6), n, n % 1000 + 1, 1,
                       timestamp('2026-08-17') + interval (n % 604800) second
                from seq
                """)) {
                seed.setInt(1, rows);
                return seed.executeUpdate();
            }
        });
    }

    private void truncateAll() {
        jdbc.execute("truncate table purchase_activity");
        jdbc.execute("truncate table ranking_snapshot");
        jdbc.execute("truncate table ranking_snapshot_input");
        jdbc.execute("truncate table ranking_score_stage");
    }

    private record Measurement(long baselineMb, long peakAfterGcMb, int gcCount, long elapsedMs) {

        long growthMb() {
            return peakAfterGcMb - baselineMb;
        }

        @Override
        public String toString() {
            return "baselineMb=%d peakAfterGcMb=%d growthMb=%d gcCount=%d elapsedMs=%d"
                .formatted(baselineMb, peakAfterGcMb, growthMb(), gcCount, elapsedMs);
        }
    }
}
