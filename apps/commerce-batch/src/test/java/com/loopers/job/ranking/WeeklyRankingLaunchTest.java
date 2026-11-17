package com.loopers.job.ranking;

import com.loopers.CommerceBatchApplication;
import com.loopers.batch.job.weeklyranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.weeklyranking.WeeklyRankingLaunchContextInitializer;
import com.loopers.batch.job.weeklyranking.WeeklyRankingLaunchException.Reason;
import com.loopers.batch.job.weeklyranking.WeeklyRankingLaunchProperties;
import com.loopers.batch.job.weeklyranking.WeeklyRankingLaunchRunner;
import com.loopers.batch.job.weeklyranking.WeeklyRankingLaunchRunner.LaunchResult;
import com.loopers.domain.ranking.RankingLaunchRepository;
import com.loopers.job.ranking.BatchMetadataFaultSupport.Point;
import com.loopers.job.ranking.process.WeeklyRankingFaultSupport;
import com.loopers.job.ranking.process.WeeklyRankingFaultSupport.FaultPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static com.loopers.job.ranking.RankingTestDb.countSourceOrders;
import static com.loopers.job.ranking.RankingTestDb.freshJdbc;
import static com.loopers.job.ranking.RankingTestDb.insertSource;
import static com.loopers.job.ranking.RankingTestDb.publication;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 명시 실행 진입(랭킹 설계 4.4~4.12). Runner 는 테스트마다 옵션을 바꿔 직접 만들어 호출한다. */
@SpringBootTest(properties = {
    "spring.batch.job.name=weeklyRanking",
    "spring.batch.job.enabled=false"
})
@Import({WeeklyRankingFaultSupport.class, BatchMetadataFaultSupport.class})
class WeeklyRankingLaunchTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JobOperator jobOperator;

    @Autowired
    private JobExplorer jobExplorer;

    @Autowired
    private JobRegistry jobRegistry;

    @Autowired
    @Qualifier(WeeklyRankingJobConfig.JOB_NAME)
    private Job weeklyRankingJob;

    @Autowired
    private RankingLaunchRepository launchRepository;

    @Autowired
    private Environment environment;

    @Autowired
    private FaultPlan processorFault;

    @Autowired
    private BatchMetadataFaultSupport.Plan metadataFault;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        RankingSchemaSupport.createIfAbsent(
            dataSource, RankingSchemaSupport.SOURCE_TABLE, "fixtures/weekly-ranking-source-schema.sql");
        assertThat(processorFault.isArmed()).isFalse();
        assertThat(metadataFault.isArmed()).isFalse();
    }

    @DisplayName("2026-06-01: new 는 snapshot 과 실행 이력의 최대 revision 다음 번호를 쓰며, 입력 고정 전에 실패한 번호도 다시 쓰지 않는다.")
    @Test
    void newUsesNextRevisionAcrossSnapshotsAndHistory() throws Exception {
        LocalDate week = LocalDate.of(2026, 6, 1);
        insertRows(week, 6101);

        LaunchResult first = launch("new", "2026-06-01", null);
        assertThat(first.exitCode()).isZero();
        assertThat(revisionOf(first)).isEqualTo(1L);

        metadataFault.arm(WeeklyRankingJobConfig.FREEZE_STEP, Point.IN_CHUNK);
        LaunchResult failedBeforeSnapshot = launch("new", "2026-06-01", null);
        assertThat(failedBeforeSnapshot.exitCode()).isEqualTo(1);
        assertThat(failedBeforeSnapshot.status()).isEqualTo(BatchStatus.FAILED);
        assertThat(revisionOf(failedBeforeSnapshot)).isEqualTo(2L);
        assertThat(launchRepository.findMaxSnapshotRevision(week)).hasValue(1L);

        LaunchResult third = launch("new", "2026-06-01", null);
        assertThat(third.exitCode()).isZero();
        assertThat(revisionOf(third)).as("snapshot 없이 실행 기록만 남은 r2 를 다시 쓰지 않는다").isEqualTo(3L);
        assertThat(publication(week)).startsWith("weekly-20260601-r3@");
        log("06-01 r1=" + first.executionId() + " r2(FAILED before snapshot)=" + failedBeforeSnapshot.executionId()
            + " r3=" + third.executionId());
    }

    @DisplayName("2026-06-08: restart 는 같은 instance 의 최신 FAILED 실행만 이어가고, 과거·완료 실행 ID 와 잘못된 옵션 조합은 거절한다.")
    @Test
    void restartAcceptsOnlyLatestFailedExecution() throws Exception {
        insertRows(LocalDate.of(2026, 6, 8), 6801);

        processorFault.armBeforeProduct(9L);
        LaunchResult failed = launch("new", "2026-06-08", null);
        assertThat(failed.status()).isEqualTo(BatchStatus.FAILED);
        processorFault.armBeforeProduct(9L);
        LaunchResult failedAgain = launch("restart", null, failed.executionId());
        assertThat(failedAgain.status()).isEqualTo(BatchStatus.FAILED);
        assertThat(failedAgain.instanceId()).isEqualTo(failed.instanceId());

        assertThat(launch("restart", null, failed.executionId()).rejection()).isEqualTo(Reason.NOT_LATEST_EXECUTION);
        assertThat(jobExplorer.getJobExecutions(jobExplorer.getJobInstance(failed.instanceId()))).hasSize(2);

        LaunchResult restarted = launch("restart", null, failedAgain.executionId());
        assertThat(restarted.exitCode()).isZero();
        assertThat(restarted.instanceId()).isEqualTo(failed.instanceId());
        assertThat(restarted.executionId()).isNotEqualTo(failedAgain.executionId());
        assertThat(launch("restart", null, restarted.executionId()).rejection()).isEqualTo(Reason.NOT_RESTARTABLE);

        assertThat(launch("new", "2026-06-08", 1L).rejection()).isEqualTo(Reason.INVALID_OPTION);
        assertThat(launch("restart", "2026-06-08", restarted.executionId()).rejection()).isEqualTo(Reason.INVALID_OPTION);
        assertThat(launch(null, "2026-06-08", null).rejection()).isEqualTo(Reason.INVALID_OPTION);
        assertThat(launch("new", "2026-06-09", null).rejection()).isEqualTo(Reason.INVALID_OPTION);
        assertThat(launch("new", "2026-06-08", null, "extra").rejection()).isEqualTo(Reason.INVALID_OPTION);
        log("06-08 instance=" + failed.instanceId() + " failed=" + failed.executionId() + " failedAgain="
            + failedAgain.executionId() + " restarted=" + restarted.executionId());
    }

    @DisplayName("2026-06-15: 같은 기간에 UNKNOWN 실행이 있으면 new·restart 모두 시작하지 않는다.")
    @Test
    void unknownExecutionBlocksNewAndRestart() throws Exception {
        insertRows(LocalDate.of(2026, 6, 15), 6151);
        metadataFault.arm(WeeklyRankingJobConfig.PUBLISH_STEP, Point.FINAL);
        LaunchResult unknown = launch("new", "2026-06-15", null);
        assertThat(unknown.status()).isEqualTo(BatchStatus.UNKNOWN);
        assertThat(unknown.exitCode()).isEqualTo(1);
        long instancesBefore = jobExplorer.getJobInstanceCount(WeeklyRankingJobConfig.JOB_NAME);

        assertThat(launch("new", "2026-06-15", null).rejection()).isEqualTo(Reason.IN_PROGRESS_OR_UNKNOWN);
        assertThat(launch("restart", null, unknown.executionId()).rejection()).isEqualTo(Reason.IN_PROGRESS_OR_UNKNOWN);
        assertThat(jobExplorer.getJobInstanceCount(WeeklyRankingJobConfig.JOB_NAME)).isEqualTo(instancesBefore);
    }

    @DisplayName("명시 launch 에 보존 설정이 아니면 initializer 가 context 초기화 전에 기동을 거절한다.")
    @Test
    void initializerRejectsNonPreservingSettings() {
        Map<String, Object> valid = new HashMap<>(Map.of(
            "weekly-ranking.launch.enabled", "true",
            "spring.batch.job.name", "weeklyRanking",
            "spring.batch.job.enabled", "false",
            "spring.jpa.hibernate.ddl-auto", "none",
            "spring.batch.jdbc.initialize-schema", "never"));
        initialize(valid);
        for (String key : valid.keySet().stream().filter(key -> !key.startsWith("weekly-ranking")).toList()) {
            Map<String, Object> broken = new HashMap<>(valid);
            broken.put(key, "update");
            assertThatThrownBy(() -> initialize(broken)).isInstanceOf(IllegalStateException.class).hasMessageContaining(key);
        }

        // 공통 시작 경로도 같은 검사를 거친다. 거절은 bean·DB 초기화 전에 일어난다.
        assertThatThrownBy(() -> CommerceBatchApplication.createApplication().run(
            "--weekly-ranking.launch.enabled=true", "--spring.batch.job.name=weeklyRanking",
            "--spring.batch.job.enabled=false", "--spring.jpa.hibernate.ddl-auto=update",
            "--spring.batch.jdbc.initialize-schema=never"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("spring.jpa.hibernate.ddl-auto");
    }

    private void initialize(Map<String, Object> properties) {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
            new WeeklyRankingLaunchContextInitializer().initialize(context);
        }
    }

    private LaunchResult launch(String mode, String periodStart, Long executionId, String... nonOptionArgs) throws Exception {
        WeeklyRankingLaunchRunner runner = new WeeklyRankingLaunchRunner(
            new WeeklyRankingLaunchProperties(new WeeklyRankingLaunchProperties.Launch(true), mode, periodStart, executionId),
            jobOperator, jobExplorer, jobRegistry, weeklyRankingJob, launchRepository, environment);
        runner.run(new DefaultApplicationArguments(nonOptionArgs));
        assertThat(runner.getExitCode()).isEqualTo(runner.getResult().exitCode());
        return runner.getResult();
    }

    private long revisionOf(LaunchResult result) {
        return jobExplorer.getJobExecution(result.executionId()).getJobParameters().getLong("revision");
    }

    private void insertRows(LocalDate week, long orderBase) {
        assertThat(countSourceOrders(jdbc, orderBase, orderBase + 1)).isZero();
        insertSource(jdbc, orderBase, 7L, 2L, week.atStartOfDay().plusHours(1));
        insertSource(jdbc, orderBase, 8L, 1L, week.atStartOfDay().plusHours(1));
        insertSource(jdbc, orderBase + 1, 9L, 5L, week.plusDays(1).atStartOfDay());
        assertThat(freshJdbc().queryForObject("SELECT COUNT(*) FROM ranking_snapshot WHERE period_start = ?",
            Long.class, week)).isZero();
    }

    private static void log(String message) {
        System.out.println("[ranking-launch] " + message);
    }
}
