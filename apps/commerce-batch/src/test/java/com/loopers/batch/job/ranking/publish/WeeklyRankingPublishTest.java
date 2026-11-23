package com.loopers.batch.job.ranking.publish;

import com.loopers.batch.job.ranking.RankingTables;
import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.ranking.WeeklyRankingParameters;
import com.loopers.batch.job.ranking.WeeklyRankingSnapshotRepository;
import com.loopers.batch.job.ranking.publish.WeeklyRankingPublisher.PublishResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

/** 9주차 설계 3장(공개). 완성본만 공개하고, 공개 실패 · 재실행 · 오래된 revision 에서 기존 공개본을 지킨다 */
@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME
})
class WeeklyRankingPublishTest {

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    @Qualifier(WeeklyRankingJobConfig.JOB_NAME)
    private Job job;

    @Autowired
    private WeeklyRankingPublisher publisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    private WeeklyRankingSnapshotRepository snapshots;

    @Autowired
    private RankingTables tables;

    @AfterEach
    void tearDown() {
        tables.truncate();
    }

    @DisplayName("job 이 끝나면 점수 내림차순 · 동점은 productId 오름차순으로 순위가 매겨지고 그 snapshot 이 공개본이 된다.")
    @Test
    void publishesRankedSnapshot() throws Exception {
        // arrange
        LocalDate week = LocalDate.parse("2026-11-02");
        tables.confirmLabFixture(week);
        WeeklyRankingParameters params = WeeklyRankingParameters.of(week, 1);

        // act
        JobExecution execution = jobLauncher.run(job, params.toJobParameters());

        // assert
        assertAll(
            () -> assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED),
            () -> assertThat(tables.manifestOf(params.snapshotId())).containsEntry("status", "PUBLISHED"),
            () -> assertThat(tables.resultChecksumOf(params.snapshotId())).isEqualTo(RankingTables.sha256("7\t5\t1\n9\t5\t2\n8\t1\t3\n"))
        );
    }

    @DisplayName("공개 TX 가 공개 전환 직전에 실패하면, 순위 · 기존 공개본 내리기가 함께 rollback 되어 이전 revision 이 공개본으로 남고, 재시작하면 새 revision 이 공개된다.")
    @Test
    void keepsPreviousPublishedWhenPublishFails() throws Exception {
        // arrange: r1 공개 뒤 늦은 구매가 들어와 r2 를 계산
        LocalDate week = LocalDate.parse("2026-11-09");
        tables.confirmLabFixture(week);
        WeeklyRankingParameters r1 = WeeklyRankingParameters.of(week, 1);
        WeeklyRankingParameters r2 = WeeklyRankingParameters.of(week, 2);
        jobLauncher.run(job, r1.toJobParameters());
        tables.confirm(45L, week.plusDays(3) + " 01:00:00", Map.of(8L, 10));
        doThrow(new IllegalStateException("before promote")).when(snapshots).promote(eq(r2.snapshotId()), any());

        // act
        JobExecution failed = jobLauncher.run(job, r2.toJobParameters());

        // assert
        assertAll(
            () -> assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED),
            () -> assertThat(tables.manifestOf(r1.snapshotId())).containsEntry("status", "PUBLISHED"),
            () -> assertThat(tables.manifestOf(r2.snapshotId())).containsEntry("status", "COMPLETED"),
            () -> assertThat(tables.scoresOf(r2.snapshotId())).extracting(row -> row.get("ranking")).containsOnlyNulls()
        );

        // act: 같은 파라미터로 재시작
        Mockito.reset(snapshots);
        JobExecution resumed = jobLauncher.run(job, r2.toJobParameters());

        // assert: 늦은 구매(상품 8 에 10개)가 반영된 r2 가 공개본
        assertAll(
            () -> assertThat(resumed.getStatus()).isEqualTo(BatchStatus.COMPLETED),
            () -> assertThat(tables.manifestOf(r1.snapshotId())).containsEntry("status", "COMPLETED"),
            () -> assertThat(tables.manifestOf(r2.snapshotId())).containsEntry("status", "PUBLISHED"),
            () -> assertThat(tables.scoresOf(r2.snapshotId()))
                .extracting(row -> row.get("product_id"), row -> row.get("score"), row -> row.get("ranking"))
                .containsExactly(tuple(7L, 5L, 2), tuple(8L, 11L, 1), tuple(9L, 5L, 3)),
            () -> assertThat(tables.resultChecksumOf(r1.snapshotId())).isEqualTo(RankingTables.sha256("7\t5\t1\n9\t5\t2\n8\t1\t3\n"))
        );
    }

    @DisplayName("이미 공개된 snapshot 을 다시 공개하면, 아무것도 바꾸지 않고 같은 결과로 끝난다.")
    @Test
    void republishingConverges() throws Exception {
        // arrange
        LocalDate week = LocalDate.parse("2026-11-16");
        tables.confirmLabFixture(week);
        WeeklyRankingParameters params = WeeklyRankingParameters.of(week, 1);
        jobLauncher.run(job, params.toJobParameters());
        String before = tables.resultChecksumOf(params.snapshotId());

        // act: 공개 commit 뒤 실행 기록만 실패해 공개를 다시 한 경우
        PublishResult result = new TransactionTemplate(transactionManager).execute(status -> publisher.publish(params));

        // assert
        assertAll(
            () -> assertThat(result).isEqualTo(PublishResult.ALREADY_PUBLISHED),
            () -> assertThat(tables.manifestOf(params.snapshotId())).containsEntry("status", "PUBLISHED"),
            () -> assertThat(tables.resultChecksumOf(params.snapshotId())).isEqualTo(before)
        );
    }

    @DisplayName("더 새 revision 이 이미 공개됐으면, 늦게 끝난 오래된 revision 의 공개는 실패하고 공개본을 되돌리지 않는다.")
    @Test
    void staleRevisionDoesNotOverwrite() throws Exception {
        // arrange: r1 은 공개 직전에 멈췄고, 그사이 r2 가 공개됨
        LocalDate week = LocalDate.parse("2026-11-23");
        tables.confirmLabFixture(week);
        WeeklyRankingParameters r1 = WeeklyRankingParameters.of(week, 1);
        WeeklyRankingParameters r2 = WeeklyRankingParameters.of(week, 2);
        doThrow(new IllegalStateException("before promote")).when(snapshots).promote(eq(r1.snapshotId()), any());
        jobLauncher.run(job, r1.toJobParameters());
        Mockito.reset(snapshots);
        jobLauncher.run(job, r2.toJobParameters());

        // act
        JobExecution stale = jobLauncher.run(job, r1.toJobParameters());

        // assert
        assertAll(
            () -> assertThat(stale.getStatus()).isEqualTo(BatchStatus.FAILED),
            () -> assertThat(stale.getAllFailureExceptions()).anySatisfy(e -> assertThat(e).hasMessageContaining("더 새 revision")),
            () -> assertThat(tables.manifestOf(r1.snapshotId())).containsEntry("status", "COMPLETED"),
            () -> assertThat(tables.manifestOf(r2.snapshotId())).containsEntry("status", "PUBLISHED")
        );
    }

    @DisplayName("구매가 없는 주도 빈 완성본으로 공개된다.")
    @Test
    void publishesEmptyWeek() throws Exception {
        // arrange
        WeeklyRankingParameters params = WeeklyRankingParameters.of(LocalDate.parse("2026-11-30"), 1);

        // act
        JobExecution execution = jobLauncher.run(job, params.toJobParameters());

        // assert
        assertAll(
            () -> assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED),
            () -> assertThat(tables.manifestOf(params.snapshotId()))
                .containsEntry("status", "PUBLISHED")
                .containsEntry("input_count", 0),
            () -> assertThat(tables.scoresOf(params.snapshotId())).isEmpty()
        );
    }
}
