package com.loopers.batch.job.ranking;

import static com.loopers.batch.job.ranking.WeeklyRankingJobFixture.WEEK;
import static com.loopers.batch.job.ranking.WeeklyRankingJobFixture.snapshotId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;

@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME
})
@Import(WeeklyRankingJobFixture.class)
@Sql("/sql/purchase-activity.sql")
class WeeklyRankingParametersValidationTest {

    @Autowired
    private WeeklyRankingJobFixture fixture;

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private JobExplorer jobExplorer;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    @Qualifier(WeeklyRankingJobConfig.JOB_NAME)
    private Job job;

    @BeforeEach
    void setUp() {
        fixture.emptyTables();
        fixture.insertActivity(42L, 7L, 2, LocalDateTime.parse("2026-08-17T00:00:00"));
    }

    @AfterEach
    void tearDown() {
        new JobRepositoryTestUtils(jobRepository).removeJobExecutions();
    }

    @DisplayName("SNP-008: snapshotId 가 기간과 revision 으로 만든 값과 다르면 launch 를 거절해 JobInstance 도 입력도 남기지 않는다.")
    @Test
    void rejectsSnapshotIdNotMatchingPeriodAndRevision() {
        assertRejectedWithoutTrace(new JobParametersBuilder()
            .addLocalDate("periodStart", WEEK)
            .addLong("revision", 1L)
            .addString("snapshotId", snapshotId(2))
            .toJobParameters());
    }

    @DisplayName("JOB-002: 식별 parameter 가 빠지면 launch 를 거절한다.")
    @Test
    void rejectsMissingSnapshotId() {
        assertRejectedWithoutTrace(new JobParametersBuilder()
            .addLocalDate("periodStart", WEEK)
            .addLong("revision", 1L)
            .toJobParameters());
    }

    @DisplayName("JOB-002: run.id 같은 다른 식별 parameter 가 붙으면 launch 를 거절한다.")
    @Test
    void rejectsUnknownIdentifyingParameter() {
        assertRejectedWithoutTrace(new JobParametersBuilder()
            .addLocalDate("periodStart", WEEK)
            .addLong("revision", 1L)
            .addString("snapshotId", snapshotId(1))
            .addLong("run.id", 1L)
            .toJobParameters());
    }

    @DisplayName("JOB-002: revision 이 식별 parameter 가 아니면 launch 를 거절한다.")
    @Test
    void rejectsNonIdentifyingRevision() {
        assertRejectedWithoutTrace(new JobParametersBuilder()
            .addLocalDate("periodStart", WEEK)
            .addLong("revision", 1L, false)
            .addString("snapshotId", snapshotId(1))
            .toJobParameters());
    }

    private void assertRejectedWithoutTrace(JobParameters params) {
        assertThatThrownBy(() -> jobLauncher.run(job, params))
            .isInstanceOf(JobParametersInvalidException.class);
        assertAll(
            () -> assertThat(jobExplorer.getJobInstances(WeeklyRankingJobConfig.JOB_NAME, 0, 10)).isEmpty(),
            () -> assertThat(fixture.countRows("ranking_snapshot_input")).isZero(),
            () -> assertThat(fixture.countRows("ranking_snapshot")).isZero()
        );
    }
}
