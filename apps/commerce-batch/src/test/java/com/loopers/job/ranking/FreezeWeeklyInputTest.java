package com.loopers.job.ranking;

import com.loopers.batch.job.ranking.RankingSnapshotStore;
import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.ranking.step.FreezeWeeklyInputTasklet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.test.MetaDataInstanceFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ① 입력 고정 step. 실제 MySQL·JobRepository로 실행한다.
 * 테스트마다 서로 다른 주를 써서 JobInstance가 겹치지 않게 한다 (Batch metadata는 지우지 않는다).
 */
@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME
})
class FreezeWeeklyInputTest {

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    @Qualifier(WeeklyRankingJobConfig.JOB_NAME)
    private Job job;

    @Autowired
    private RankingSnapshotStore store;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Clock clock;

    @Autowired
    private DataSource dataSource;

    private RankingFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new RankingFixtures(dataSource);
        fixtures.clearData();
    }

    @DisplayName("그 주의 활동만 시작 포함·끝 제외로 복사하고, 같은 주문의 여러 품목을 모두 담아 건수·상품 수·checksum과 함께 기록한다.")
    @Test
    void freezesWeekWithAllItemsAndBoundaries() throws Exception {
        // 2026-08-31 주. 경계 바로 앞·뒤 활동은 들어오지 않는다.
        fixtures.activity(40, 7, 9, "2026-08-30T23:59:59.999999Z");
        fixtures.activity(42, 7, 2, "2026-08-31T00:00:00Z");
        fixtures.activity(42, 8, 1, "2026-08-31T00:00:00Z");
        fixtures.activity(43, 7, 3, "2026-09-01T01:00:00Z");
        fixtures.activity(44, 9, 5, "2026-09-06T23:59:59.999999Z");
        fixtures.activity(45, 7, 9, "2026-09-07T00:00:00Z");

        JobExecution execution = jobLauncher.run(job, params("2026-08-31", "weekly-20260831-r1"));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(fixtures.snapshotInput("weekly-20260831-r1")).containsExactly(
            Map.of("order_id", 42L, "product_id", 7L, "quantity", 2),
            Map.of("order_id", 42L, "product_id", 8L, "quantity", 1),
            Map.of("order_id", 43L, "product_id", 7L, "quantity", 3),
            Map.of("order_id", 44L, "product_id", 9L, "quantity", 5)
        );
        Map<String, Object> snapshot = fixtures.snapshot("weekly-20260831-r1");
        // job 전체(고정 → 계산 → 공개)를 실행하므로 최종 상태는 COMPLETED다. 고정 단계가 남긴 건수·checksum은 그대로다.
        assertThat(snapshot.get("status")).isEqualTo("COMPLETED");
        assertThat(snapshot.get("revision")).isEqualTo(1);
        assertThat(snapshot.get("input_count")).isEqualTo(4);
        assertThat(snapshot.get("product_count")).isEqualTo(3);
        assertThat(snapshot.get("frozen_at")).isNotNull();
        // checksum은 정해진 직렬화(TAB·LF·마지막 LF·UTC 마이크로초)를 그대로 해시한 값이어야 한다.
        assertThat(snapshot.get("input_checksum")).isEqualTo(sha256("""
            42\t7\t2\t2026-08-31T00:00:00.000000Z
            42\t8\t1\t2026-08-31T00:00:00.000000Z
            43\t7\t3\t2026-09-01T01:00:00.000000Z
            44\t9\t5\t2026-09-06T23:59:59.999999Z
            """));
    }

    @DisplayName("이미 고정된 snapshot을 다시 고정하면 그 사이 늦게 도착한 같은 주 구매를 추가하지 않고 기존 입력을 그대로 쓴다.")
    @Test
    void keepsFrozenInput_whenLatePurchaseArrives() throws Exception {
        fixtures.assignmentWeek("2026-09-07T00:00:00Z");
        jobLauncher.run(job, params("2026-09-07", "weekly-20260907-r1"));
        String checksum = (String) fixtures.snapshot("weekly-20260907-r1").get("input_checksum");

        fixtures.activity(46, 8, 7, "2026-09-08T03:00:00Z");
        StepContribution contribution = freezeAgain("2026-09-07", "weekly-20260907-r1");

        assertThat(contribution.getExitStatus().getExitCode()).isEqualTo("ALREADY_FROZEN");
        assertThat(fixtures.snapshotInput("weekly-20260907-r1")).hasSize(4);
        assertThat(fixtures.snapshot("weekly-20260907-r1").get("input_checksum")).isEqualTo(checksum);
    }

    @DisplayName("같은 주에 기존 revision 이하로 새 snapshot을 만들면 덮어쓰지 않고 충돌로 실패한다.")
    @Test
    void rejectsRevisionNotGreaterThanExisting() throws Exception {
        fixtures.assignmentWeek("2026-09-14T00:00:00Z");
        jobLauncher.run(job, params("2026-09-14", "weekly-20260914-r2"));

        JobExecution older = jobLauncher.run(job, params("2026-09-14", "weekly-20260914-r1"));

        assertThat(older.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(older.getAllFailureExceptions()).anySatisfy(e ->
            assertThat(e).hasMessageContaining("revision은 기존 최대값보다 커야"));
        assertThat(fixtures.snapshots()).extracting(row -> row.get("snapshot_id")).containsExactly("weekly-20260914-r2");
        assertThat(fixtures.snapshotInput("weekly-20260914-r1")).isEmpty();
    }

    @DisplayName("수량이 0 이하인 활동이 있으면 skip하지 않고 실패하며, 복사하던 입력과 snapshot 정보가 함께 rollback되어 반쪽 snapshot이 남지 않는다.")
    @Test
    void rollsBackWholeSnapshot_whenInvalidQuantity() throws Exception {
        fixtures.activity(42, 7, 2, "2026-09-21T01:00:00Z");
        fixtures.activity(43, 8, 0, "2026-09-22T01:00:00Z");

        JobExecution execution = jobLauncher.run(job, params("2026-09-21", "weekly-20260921-r1"));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(fixtures.snapshots()).isEmpty();
        assertThat(fixtures.snapshotInput("weekly-20260921-r1")).isEmpty();
    }

    @DisplayName("periodStart가 월요일이 아니거나 snapshotId의 날짜가 다르면 실행을 만들지 않고 거부한다.")
    @Test
    void rejectsInvalidParameters() {
        assertThatThrownBy(() -> jobLauncher.run(job, params("2026-09-29", "weekly-20260929-r1")))
            .isInstanceOf(JobParametersInvalidException.class)
            .hasMessageContaining("월요일");
        assertThatThrownBy(() -> jobLauncher.run(job, params("2026-09-28", "weekly-20260921-r1")))
            .isInstanceOf(JobParametersInvalidException.class)
            .hasMessageContaining("periodStart와 다릅니다");
        assertThatThrownBy(() -> jobLauncher.run(job, params("2026-09-28", "run-1")))
            .isInstanceOf(JobParametersInvalidException.class);
    }

    private JobParameters params(String periodStart, String snapshotId) {
        return new JobParametersBuilder()
            .addString("periodStart", periodStart, true)
            .addString("snapshotId", snapshotId, true)
            .toJobParameters();
    }

    /** 공개 후 metadata만 실패한 경우처럼, 이미 고정된 snapshot에 대해 같은 tasklet을 실제 트랜잭션 안에서 다시 실행한다. */
    private StepContribution freezeAgain(String periodStart, String snapshotId) {
        var tasklet = new FreezeWeeklyInputTasklet(store, clock, periodStart, snapshotId);
        var contribution = new StepContribution(MetaDataInstanceFactory.createStepExecution());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> tasklet.execute(contribution, null));
        return contribution;
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
