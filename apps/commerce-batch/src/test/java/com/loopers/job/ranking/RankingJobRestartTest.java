package com.loopers.job.ranking;

import com.loopers.batch.job.ranking.ProductScore;
import com.loopers.batch.job.ranking.RankingSnapshotStore;
import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * 과제 §6 재시작 실험. chunk=2로 상품 7·8을 commit한 뒤 상품 9 직전에 실패시키고, 같은 식별 parameter·같은 DB metadata로 이어 간다.
 * 실패 스위치는 job parameter가 아니라 테스트의 spy다. run.id·현재 시각을 붙이지 않는다.
 */
@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
    "ranking.weekly.chunk-size=2"
})
class RankingJobRestartTest {

    private static final String READ_COUNT_KEY = WeeklyRankingJobConfig.PRODUCT_READER + ".read.count";

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    @Qualifier(WeeklyRankingJobConfig.JOB_NAME)
    private Job job;

    @Autowired
    private JobExplorer jobExplorer;

    @MockitoSpyBean
    private RankingSnapshotStore store;

    @Autowired
    private DataSource dataSource;

    private RankingFixtures fixtures;

    /** 이 상품의 점수 계산을 한 번 실패시킨다. 0이면 실패시키지 않는다. */
    private final AtomicLong failOnceAt = new AtomicLong();

    @BeforeEach
    void setUp() {
        fixtures = new RankingFixtures(dataSource);
        fixtures.clearData();
        failOnceAt.set(0);
        doAnswer(invocation -> {
            long productId = invocation.getArgument(1);
            if (failOnceAt.compareAndSet(productId, 0)) {
                throw new IllegalStateException("상품 " + productId + " 계산 직전 실패(테스트)");
            }
            return invocation.callRealMethod();
        }).when(store).sumQuantity(anyString(), anyLong());
    }

    @DisplayName("상품 9 직전에 실패한 실행을 같은 parameter로 재시작하면, 같은 JobInstance의 새 실행이 입력 고정을 다시 하지 않고 상품 9부터 이어서 "
        + "정상 한 번 실행과 같은 점수·순위·checksum을 만들고, 실패 동안에는 기존 공개본이 유지된다.")
    @Test
    void restartsFromProductNine_andMatchesNormalRun() throws Exception {
        fixtures.assignmentWeek("2026-08-17T00:00:00Z");

        // 비교용 정상 실행: 같은 입력을 별도 snapshot(r1)으로 한 번에 끝까지 계산·공개한다. 재시작 대상의 metadata·stage를 지워 위장하지 않는다.
        JobExecution normal = jobLauncher.run(job, params("2026-08-17", "weekly-20260817-r1"));
        assertThat(normal.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // 재시작 대상(r2) 1차 실행: 7·8 chunk는 commit되고, 세 번째 item인 상품 9를 처리하기 직전에 실패한다.
        JobParameters params = params("2026-08-17", "weekly-20260817-r2");
        failOnceAt.set(9L);
        JobExecution first = jobLauncher.run(job, params);

        StepExecution firstCalc = step(first, WeeklyRankingJobConfig.CALCULATE_STEP).orElseThrow();
        StepExecution persistedCalc = jobExplorer.getStepExecution(first.getId(), firstCalc.getId());
        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(step(first, WeeklyRankingJobConfig.FREEZE_STEP).orElseThrow().getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(step(first, WeeklyRankingJobConfig.PUBLISH_STEP)).isEmpty();
        assertThat(persistedCalc.getExecutionContext().getInt(READ_COUNT_KEY)).isEqualTo(2);
        assertThat(persistedCalc.getCommitCount()).isEqualTo(1);
        assertThat(fixtures.stage("weekly-20260817-r2")).containsExactly(
            Map.of("product_id", 7L, "score", 5L),
            Map.of("product_id", 8L, "score", 1L)
        );
        assertThat(fixtures.ranking("weekly-20260817-r2")).isEmpty();
        assertThat(fixtures.publications()).extracting(row -> row.get("snapshot_id")).containsExactly("weekly-20260817-r1");
        String inputChecksum = (String) fixtures.snapshot("weekly-20260817-r2").get("input_checksum");

        // 실패와 재시작 사이에 같은 주의 늦은 구매가 도착해도 현재 snapshot 입력은 바뀌지 않아야 한다.
        fixtures.activity(46, 8, 7, "2026-08-18T05:00:00Z");

        // 2차 실행: 입력·stage·Batch metadata를 지우지 않고 같은 parameter로 다시 실행한다.
        JobExecution second = jobLauncher.run(job, params);

        StepExecution secondCalc = step(second, WeeklyRankingJobConfig.CALCULATE_STEP).orElseThrow();
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getJobInstance().getInstanceId()).isEqualTo(first.getJobInstance().getInstanceId());
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(step(second, WeeklyRankingJobConfig.FREEZE_STEP)).as("완료한 입력 고정 step은 다시 실행하지 않는다").isEmpty();
        assertThat(secondCalc.getReadCount()).isEqualTo(1);
        assertThat(secondCalc.getWriteCount()).isEqualTo(1);
        assertThat(fixtures.snapshotInput("weekly-20260817-r2")).hasSize(4);

        // 손으로 계산한 기대값: 7(5점·1위), 9(5점·2위, 동점이면 productId 오름차순), 8(1점·3위).
        List<Map<String, Object>> expected = List.of(
            Map.of("product_id", 7L, "score", 5L, "rank_no", 1),
            Map.of("product_id", 9L, "score", 5L, "rank_no", 2),
            Map.of("product_id", 8L, "score", 1L, "rank_no", 3)
        );
        assertThat(fixtures.ranking("weekly-20260817-r2")).containsExactlyElementsOf(expected);
        assertThat(fixtures.ranking("weekly-20260817-r1")).containsExactlyElementsOf(expected);
        Map<String, Object> normalSnapshot = fixtures.snapshot("weekly-20260817-r1");
        Map<String, Object> restartedSnapshot = fixtures.snapshot("weekly-20260817-r2");
        assertThat(restartedSnapshot.get("input_checksum")).isEqualTo(inputChecksum).isEqualTo(normalSnapshot.get("input_checksum"));
        assertThat(restartedSnapshot.get("result_checksum")).isEqualTo(normalSnapshot.get("result_checksum"))
            .isEqualTo(RankingFixtures.sha256("7\t5\t1\n9\t5\t2\n8\t1\t3\n"));
        assertThat(fixtures.publications()).extracting(row -> row.get("snapshot_id")).containsExactly("weekly-20260817-r2");
    }

    @DisplayName("첫 chunk가 commit되기 전에 실패하면 점수도 reader 위치도 남지 않고, 재시작은 처음 상품부터 다시 계산해 같은 점수로 수렴한다.")
    @Test
    void reprocessesFirstChunk_whenFailedBeforeCommit() throws Exception {
        fixtures.assignmentWeek("2026-10-05T00:00:00Z");
        JobParameters params = params("2026-10-05", "weekly-20261005-r1");

        failOnceAt.set(8L);
        JobExecution first = jobLauncher.run(job, params);

        StepExecution firstCalc = step(first, WeeklyRankingJobConfig.CALCULATE_STEP).orElseThrow();
        StepExecution persistedCalc = jobExplorer.getStepExecution(first.getId(), firstCalc.getId());
        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(persistedCalc.getCommitCount()).isZero();
        assertThat(persistedCalc.getExecutionContext().getInt(READ_COUNT_KEY, 0)).isZero();
        assertThat(fixtures.stage("weekly-20261005-r1")).isEmpty();

        JobExecution second = jobLauncher.run(job, params);

        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(step(second, WeeklyRankingJobConfig.CALCULATE_STEP).orElseThrow().getWriteCount()).isEqualTo(3);
        assertThat(fixtures.stage("weekly-20261005-r1")).containsExactly(
            Map.of("product_id", 7L, "score", 5L),
            Map.of("product_id", 8L, "score", 1L),
            Map.of("product_id", 9L, "score", 5L)
        );
    }

    @DisplayName("같은 상품 점수를 다시 써도 더해지지 않고 같은 절대값으로 수렴한다 (5 → 5).")
    @Test
    void rewritingSameProductConverges() {
        store.upsertScores("weekly-20261012-r1", List.of(new ProductScore(7, 5)));
        store.upsertScores("weekly-20261012-r1", List.of(new ProductScore(7, 5)));

        assertThat(fixtures.stage("weekly-20261012-r1")).containsExactly(Map.of("product_id", 7L, "score", 5L));
    }

    private JobParameters params(String periodStart, String snapshotId) {
        return new JobParametersBuilder()
            .addString("periodStart", periodStart, true)
            .addString("snapshotId", snapshotId, true)
            .toJobParameters();
    }

    private Optional<StepExecution> step(JobExecution execution, String stepName) {
        return execution.getStepExecutions().stream().filter(it -> it.getStepName().equals(stepName)).findFirst();
    }
}
