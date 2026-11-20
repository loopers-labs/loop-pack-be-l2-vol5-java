package com.loopers.job.ranking;

import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.ranking.step.FreezeWeeklyInputTasklet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.loopers.job.ranking.RankingTestSupport.utc;
import static org.assertj.core.api.Assertions.assertThat;

/** Step 1 입력 고정의 DB 동작 (설계 D6·D9): 경계 포함/제외·소프트 삭제 제외·충돌 실패 rollback·0행 완성본. */
@Import(RankingTestConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME})
class FreezeWeeklyInputStepTest {

    private static final LocalDate BOUNDARY_PERIOD = LocalDate.of(2026, 7, 27);
    /** 배정표의 2026-07-20 은 경계 테스트의 '시작 전' 행(2026-07-26T23:59:59Z)이 속하는 주라 0행이 아니다. 어느 테스트도 쓰지 않는 주로 옮겼다. */
    private static final LocalDate EMPTY_PERIOD = LocalDate.of(2026, 7, 6);

    private final Job job;
    private final JobLauncher jobLauncher;
    private final JdbcTemplate jdbcTemplate;
    private final RankingTestSupport support;

    @Autowired
    FreezeWeeklyInputStepTest(@Qualifier(WeeklyRankingJobConfig.JOB_NAME) Job job, JobLauncher jobLauncher, JdbcTemplate jdbcTemplate) {
        this.job = job;
        this.jobLauncher = jobLauncher;
        this.jdbcTemplate = jdbcTemplate;
        this.support = new RankingTestSupport(jdbcTemplate);
    }

    @BeforeAll
    void createInputTable() {
        support.createPurchaseActivityTable();
    }

    @DisplayName("기간 시작은 포함·끝은 제외하고, 소프트 삭제 행은 복사하지 않는다. 같은 (기간, revision) 회차가 이미 있으면 Step 1 은 실패하고 입력은 rollback 된다.")
    @Test
    void copiesHalfOpenRangeAndRejectsConflict() throws Exception {
        // arrange — 경계 네 행 + 삭제 행 (orderId 2000 번대)
        support.insertActivity(2001, 7, 1, utc("2026-07-27T00:00:00Z"), null);          // 포함 (시작)
        support.insertActivity(2002, 7, 1, utc("2026-08-03T00:00:00Z"), null);          // 제외 (끝)
        support.insertActivity(2003, 7, 1, utc("2026-07-26T23:59:59Z"), null);          // 제외 (시작 전)
        support.insertActivity(2004, 7, 1, utc("2026-08-02T23:59:59.999999Z"), null);   // 포함 (끝 직전)
        support.insertActivity(2005, 7, 1, utc("2026-07-28T01:00:00Z"), utc("2026-07-29T00:00:00Z")); // 삭제 → 제외
        String r1 = RankingTestSupport.snapshotId(BOUNDARY_PERIOD, 1);

        // act
        JobExecution execution = jobLauncher.run(job, RankingTestSupport.params(BOUNDARY_PERIOD, 1));

        // assert — 경계
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        List<String> copied = jdbcTemplate.queryForList(
            "SELECT date_format(confirmed_at, '%Y-%m-%dT%H:%i:%s.%fZ') FROM weekly_ranking_inputs WHERE snapshot_id = ? ORDER BY confirmed_at",
            String.class, r1);
        assertThat(copied).containsExactly("2026-07-27T00:00:00.000000Z", "2026-08-02T23:59:59.999999Z");
        assertThat(support.revision(r1).get("input_count")).isEqualTo(2);

        // arrange — D6 충돌: 같은 (period_start, revision=2) 회차 행을 테스트가 먼저 넣어 둔다
        String r2 = RankingTestSupport.snapshotId(BOUNDARY_PERIOD, 2);
        jdbcTemplate.update(
            "INSERT INTO weekly_ranking_revisions (snapshot_id, period_start, revision, status, frozen_at, input_count, "
                + "expected_product_count, input_checksum, result_checksum, created_at, updated_at) "
                + "VALUES (?, ?, 2, 'CALCULATING', ?, 0, 0, ?, NULL, ?, ?)",
            r2, BOUNDARY_PERIOD, utc("2026-08-03T00:10:00Z"), RankingTestSupport.EMPTY_CHECKSUM,
            utc("2026-08-03T00:10:00Z"), utc("2026-08-03T00:10:00Z"));

        // act
        JobExecution conflict = jobLauncher.run(job, RankingTestSupport.params(BOUNDARY_PERIOD, 2));

        // assert — Step 1 FAILED, 입력 0행(rollback), 회차 행은 테스트가 넣은 그대로
        assertThat(conflict.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(conflict.getStepExecutions()).extracting(StepExecution::getStepName).containsExactly(FreezeWeeklyInputTasklet.STEP_NAME);
        assertThat(conflict.getStepExecutions().iterator().next().getFailureExceptions())
            .singleElement().isInstanceOf(IllegalStateException.class).extracting(Throwable::getMessage).asString().contains("conflict");
        assertThat(support.countInputs(r2)).isZero();
        Map<String, Object> rev2 = support.revision(r2);
        assertThat(rev2.get("status")).isEqualTo("CALCULATING");
        assertThat(rev2.get("input_count")).isEqualTo(0);
    }

    @DisplayName("구매 활동이 0건인 주도 정상 완성본이다: 입력 0·기대 상품 0·빈 checksum 두 개·점수 0행·pointer 공개.")
    @Test
    void emptyWeekIsPublishedAsEmptyCompletedSnapshot() throws Exception {
        String r1 = RankingTestSupport.snapshotId(EMPTY_PERIOD, 1);

        JobExecution execution = jobLauncher.run(job, RankingTestSupport.params(EMPTY_PERIOD, 1));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Map<String, Object> rev = support.revision(r1);
        assertThat(rev.get("status")).isEqualTo("COMPLETED");
        assertThat(rev.get("input_count")).isEqualTo(0);
        assertThat(rev.get("expected_product_count")).isEqualTo(0);
        assertThat(rev.get("input_checksum")).isEqualTo(RankingTestSupport.EMPTY_CHECKSUM);
        assertThat(rev.get("result_checksum")).isEqualTo(RankingTestSupport.EMPTY_CHECKSUM);
        assertThat(support.scores(r1)).isEmpty();
        assertThat(support.publication(EMPTY_PERIOD)).singleElement().satisfies(p -> {
            assertThat(p.get("snapshot_id")).isEqualTo(r1);
            assertThat(p.get("revision")).isEqualTo(1);
        });
    }
}
