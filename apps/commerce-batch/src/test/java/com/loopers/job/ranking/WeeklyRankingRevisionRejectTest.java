package com.loopers.job.ranking;

import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.ranking.step.OpenWeeklyRankingTasklet;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** D8: 더 새 revision 이 이미 공개된 뒤 도착한 오래된 실행은 회차는 완성하지만 pointer 를 되돌리지 않고 COMPLETED 로 끝난다. period 2026-08-03, orderId 4000 번대. */
@Import(RankingTestConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME})
class WeeklyRankingRevisionRejectTest {

    private static final LocalDate PERIOD = LocalDate.of(2026, 8, 3);

    private final Job job;
    private final JobLauncher jobLauncher;
    private final RankingTestSupport support;

    @Autowired
    WeeklyRankingRevisionRejectTest(@Qualifier(WeeklyRankingJobConfig.JOB_NAME) Job job, JobLauncher jobLauncher, JdbcTemplate jdbcTemplate) {
        this.job = job;
        this.jobLauncher = jobLauncher;
        this.support = new RankingTestSupport(jdbcTemplate);
    }

    @BeforeAll
    void createInputTable() {
        support.createPurchaseActivityTable();
    }

    @DisplayName("r2 가 공개된 뒤 r1 을 실행하면 r1 은 COMPLETED 로 끝나지만 pointer 는 r2 를 유지하고 exitDescription 에 거절 사유가 남는다.")
    @Test
    void staleRevisionDoesNotOverridePointer() throws Exception {
        // arrange — r2 먼저 공개
        support.insertFixture(PERIOD, 4000);
        String r2 = RankingTestSupport.snapshotId(PERIOD, 2);
        JobExecution r2Run = jobLauncher.run(job, RankingTestSupport.params(PERIOD, 2));
        assertThat(r2Run.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Map<String, Object> pointerBefore = support.publication(PERIOD).get(0);
        assertThat(pointerBefore.get("snapshot_id")).isEqualTo(r2);

        // act — 오래된 r1
        String r1 = RankingTestSupport.snapshotId(PERIOD, 1);
        JobExecution r1Run = jobLauncher.run(job, RankingTestSupport.params(PERIOD, 1));

        // assert
        assertThat(r1Run.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Map<String, Object> rev1 = support.revision(r1);
        assertThat(rev1.get("status")).isEqualTo("COMPLETED");
        assertThat(rev1.get("result_checksum")).isEqualTo(support.revision(r2).get("result_checksum"));
        Map<String, Object> pointerAfter = support.publication(PERIOD).get(0);
        assertThat(pointerAfter.get("snapshot_id")).isEqualTo(r2);
        assertThat(pointerAfter.get("revision")).isEqualTo(2);
        assertThat(pointerAfter.get("published_at")).isEqualTo(pointerBefore.get("published_at"));
        StepExecution open = r1Run.getStepExecutions().stream()
            .filter(s -> s.getStepName().equals(OpenWeeklyRankingTasklet.STEP_NAME)).findFirst().orElseThrow();
        System.out.println("[reject] open step exitStatus=" + open.getExitStatus());
        assertThat(open.getExitStatus().getExitDescription()).contains("NOT_PUBLISHED: newer revision r2 already published");
    }
}
