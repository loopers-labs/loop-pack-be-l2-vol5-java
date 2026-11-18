package com.loopers.job.ranking;

import com.loopers.batch.job.ranking.ProductScore;
import com.loopers.batch.job.ranking.RankingChecksum;
import com.loopers.batch.job.ranking.SnapshotStatus;
import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.ranking.WeeklyRankingPublisher;
import com.loopers.batch.job.ranking.WeeklyRankingTarget;
import com.loopers.batch.job.ranking.step.ScoreStageWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.item.Chunk;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

/**
 * weeklyRanking job 의 경계·revision·공개 규칙. 테스트마다 다른 주를 쓰고 행·metadata 를 지우지 않는다. 시계 2026-12-01T00:00Z.
 * 실행: ./gradlew :apps:commerce-batch:test --tests '*WeeklyRankingJobTest' (Docker 필요).
 */
class WeeklyRankingJobTest extends WeeklyRankingJobTestSupport {

    @Autowired
    private WeeklyRankingPublisher publisher;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private static String failureMessage(JobExecution execution) {
        return execution.getAllFailureExceptions().stream().map(Throwable::getMessage).toList().toString();
    }

    @DisplayName("구간 시작(월 00:00Z)은 포함하고 끝(다음 월 00:00Z)은 제외한다. 앞뒤 주의 경계 활동은 들어가지 않는다.")
    @Test
    void includesStartAndExcludesEnd() throws Exception {
        db.activity(2001, 1, 1, "2026-08-31T00:00:00Z");
        db.activity(2002, 2, 2, "2026-09-06T23:59:59.999999Z");
        db.activity(2003, 3, 4, "2026-09-07T00:00:00Z");
        db.activity(2004, 4, 8, "2026-08-30T23:59:59.999999Z");
        WeeklyRankingTarget target = target("2026-08-31", 1);

        JobExecution execution = launch(target);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(db.inputs(target.snapshotId())).containsExactly(List.of(2001L, 1L, 1L), List.of(2002L, 2L, 2L));
        assertThat(db.ranking(target.snapshotId())).containsExactly(List.of(2L, 2L, 1L), List.of(1L, 1L, 2L));
        assertThat(db.snapshot(target.snapshotId()).orElseThrow().get("input_checksum")).isEqualTo(RankingChecksum.sha256(
            "2001\t1\t1\t2026-08-31T00:00:00.000000Z\n2002\t2\t2\t2026-09-06T23:59:59.999999Z\n"));
    }

    @DisplayName("구매가 없는 주도 완성본으로 공개한다: 입력 0·점수 0행·빈 문자열 checksum, pointer 는 그 snapshot.")
    @Test
    void publishesEmptyWeek() throws Exception {
        WeeklyRankingTarget target = target("2026-09-21", 1);

        JobExecution execution = launch(target);

        Map<String, Object> snapshot = db.snapshot(target.snapshotId()).orElseThrow();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(snapshot.get("status")).isEqualTo("PUBLISHED");
        assertThat(((Number) snapshot.get("input_count")).intValue()).isZero();
        assertThat(snapshot.get("input_checksum")).isEqualTo(RankingChecksum.sha256(""));
        assertThat(snapshot.get("result_checksum")).isEqualTo(RankingChecksum.sha256(""));
        assertThat(db.ranking(target.snapshotId())).isEmpty();
        assertThat(db.publishedSnapshotId(target.periodStart())).contains(target.snapshotId());
    }

    @DisplayName("기간이 끝나지 않은 주는 고정하지 않는다: 입력 고정 step 이 실패하고 snapshot·입력이 남지 않는다.")
    @Test
    void rejectsUnfinishedWeek() throws Exception {
        WeeklyRankingTarget target = target("2026-11-30", 1);
        db.activity(2101, 1, 1, "2026-11-30T01:00:00Z");

        JobExecution execution = launch(target);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failureMessage(execution)).contains("기간이 아직 끝나지 않았습니다");
        assertThat(db.snapshot(target.snapshotId())).isEmpty();
        assertThat(db.inputs(target.snapshotId())).isEmpty();
    }

    @DisplayName("월요일이 아니거나 정규 표기가 아니거나 식별 parameter 가 아니면 JobInstance 를 만들기 전에 거절한다.")
    @Test
    void rejectsInvalidParameters() {
        var tuesday = new JobParametersBuilder().addString("periodStart", "2026-07-07").addString("revision", "1").toJobParameters();
        var leadingZero = new JobParametersBuilder().addString("periodStart", "2026-07-06").addString("revision", "01").toJobParameters();
        var zero = new JobParametersBuilder().addString("periodStart", "2026-07-06").addString("revision", "0").toJobParameters();
        var notIdentifying = new JobParametersBuilder().addString("periodStart", "2026-07-06", false).addString("revision", "1").toJobParameters();

        for (var parameters : List.of(tuesday, leadingZero, zero, notIdentifying)) {
            assertThatThrownBy(() -> jobLauncher.run(job, parameters)).isInstanceOf(JobParametersInvalidException.class);
            assertThat(jobRepository.getJobInstance(WeeklyRankingJobConfig.JOB_NAME, parameters)).isNull();
        }
    }

    @DisplayName("revision 은 기간의 최대 + 1 만 받는다: r1 전 r2·r1 뒤 r3 는 실패, 같은 r2 를 r1 공개 뒤 재시작하면 완료된다.")
    @Test
    void requiresNextRevision() throws Exception {
        db.activity(2201, 1, 3, "2026-10-05T01:00:00Z");
        WeeklyRankingTarget r1 = target("2026-10-05", 1);
        WeeklyRankingTarget r2 = target("2026-10-05", 2);
        WeeklyRankingTarget r3 = target("2026-10-05", 3);

        JobExecution earlyR2 = launch(r2);
        assertThat(earlyR2.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failureMessage(earlyR2)).contains("expected = 1");
        assertThat(db.snapshot(r2.snapshotId())).isEmpty();

        assertThat(launch(r1).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        JobExecution skipped = launch(r3);
        assertThat(skipped.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failureMessage(skipped)).contains("expected = 2");
        assertThat(db.snapshot(r3.snapshotId())).isEmpty();

        JobExecution restartedR2 = launch(r2);
        assertThat(restartedR2.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(restartedR2.getJobInstance().getInstanceId()).isEqualTo(earlyR2.getJobInstance().getInstanceId());
        assertThat(db.publishedSnapshotId(r1.periodStart())).contains(r2.snapshotId());
    }

    @DisplayName("같은 기간은 직렬: r1 이 계산 중 실패해 FROZEN 이면 r2 를 고정하지 않는다. r1 을 재시작해 끝낸 뒤 r2 가 진행된다.")
    @Test
    void serializesRevisionsOfSamePeriod() throws Exception {
        db.activity(2301, 1, 2, "2026-10-19T01:00:00Z");
        db.activity(2302, 2, 1, "2026-10-20T01:00:00Z");
        WeeklyRankingTarget r1 = target("2026-10-19", 1);
        WeeklyRankingTarget r2 = target("2026-10-19", 2);
        doThrow(new IllegalStateException("주입된 실패: r1 계산")).doCallRealMethod().when(store).upsertScores(eq(r1.snapshotId()), any());

        assertThat(launch(r1).getStatus()).isEqualTo(BatchStatus.FAILED);
        JobExecution blocked = launch(r2);

        assertThat(blocked.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failureMessage(blocked)).contains("이전 revision 이 아직 공개 단계를 마치지 않았습니다");
        assertThat(db.snapshot(r2.snapshotId())).isEmpty();
        assertThat(db.publication(r1.periodStart())).isEmpty();

        assertThat(launch(r1).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(launch(r2).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(db.publishedSnapshotId(r1.periodStart())).contains(r2.snapshotId());
    }

    @DisplayName("공개 TX 안에서 실패하면 순위·pointer·상태가 모두 rollback 되어 이전 공개본(r1)이 그대로 보이고, 재시작하면 r2 가 공개된다.")
    @Test
    void rollsBackPublishTransaction() throws Exception {
        db.activity(2401, 1, 2, "2026-11-02T01:00:00Z");
        WeeklyRankingTarget r1 = target("2026-11-02", 1);
        WeeklyRankingTarget r2 = target("2026-11-02", 2);
        assertThat(launch(r1).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        db.activity(2402, 2, 5, "2026-11-03T01:00:00Z");
        doThrow(new IllegalStateException("주입된 실패: 공개 TX 의 상태 변경"))
            .doCallRealMethod()
            .when(store).markPublished(eq(r2.snapshotId()), any(SnapshotStatus.class), any(), any(), anyLong());

        JobExecution failed = launch(r2);

        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(persistedStep(failed, WeeklyRankingJobConfig.PUBLISH_STEP).getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(db.stage(r2.snapshotId())).containsExactly(List.of(1L, 2L), List.of(2L, 5L));
        assertThat(db.ranking(r2.snapshotId())).as("순위 insert 도 rollback").isEmpty();
        assertThat(db.snapshot(r2.snapshotId()).orElseThrow().get("status")).isEqualTo("FROZEN");
        assertThat(db.publishedSnapshotId(r1.periodStart())).contains(r1.snapshotId());

        JobExecution restarted = launch(r2);

        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(restarted.getStepExecutions()).extracting(StepExecution::getStepName).containsExactly(WeeklyRankingJobConfig.PUBLISH_STEP);
        assertThat(db.ranking(r2.snapshotId())).containsExactly(List.of(2L, 5L, 1L), List.of(1L, 2L, 2L));
        assertThat(db.publishedSnapshotId(r1.periodStart())).contains(r2.snapshotId());
    }

    @DisplayName("더 새 revision 이 이미 공개돼 있으면 pointer 를 되돌리지 않는다: 완성 ranking 은 만들되 SUPERSEDED 이고 job 은 완료한다.")
    @Test
    void doesNotMovePointerBackwards() throws Exception {
        db.activity(2501, 1, 1, "2026-11-16T01:00:00Z");
        WeeklyRankingTarget r1 = target("2026-11-16", 1);
        db.jdbc().update("insert into weekly_ranking_publication (period_start, snapshot_id, revision, published_at) values (?, ?, ?, ?)",
            r1.periodStart(), "weekly:2026-11-16:r9", 9, java.time.LocalDateTime.parse("2026-11-30T00:00:00"));

        JobExecution execution = launch(r1);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(persistedStep(execution, WeeklyRankingJobConfig.PUBLISH_STEP).getExecutionContext().getString("publishStatus"))
            .isEqualTo("SUPERSEDED");
        assertThat(db.snapshot(r1.snapshotId()).orElseThrow().get("status")).isEqualTo("SUPERSEDED");
        assertThat(db.ranking(r1.snapshotId())).containsExactly(List.of(1L, 1L, 1L));
        assertThat(db.publication(r1.periodStart()).orElseThrow()).containsEntry("snapshot_id", "weekly:2026-11-16:r9").containsEntry("revision", 9L);
    }

    @DisplayName("이미 있는 snapshotId 의 입력이 기록과 다르면 덮어쓰지 않고 충돌로 실패한다.")
    @Test
    void rejectsConflictingSnapshot() throws Exception {
        WeeklyRankingTarget target = target("2026-07-06", 1);
        db.activity(2601, 1, 1, "2026-07-06T01:00:00Z");
        db.jdbc().update("insert into ranking_snapshot (snapshot_id, period_start, period_end, revision, status, frozen_at, input_count, "
                + "product_count, input_checksum, frozen_by_execution_id) values (?, ?, ?, 1, 'FROZEN', '2026-07-13 00:00:00', 1, 1, ?, 0)",
            target.snapshotId(), target.periodStart(), target.periodEnd(), "0".repeat(64));

        JobExecution execution = launch(target);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failureMessage(execution)).contains("덮어쓰지 않습니다");
        assertThat(db.inputs(target.snapshotId())).isEmpty();
        assertThat(db.snapshot(target.snapshotId()).orElseThrow()).containsEntry("input_checksum", "0".repeat(64)).containsEntry("status", "FROZEN");
    }

    @DisplayName("공개 단계를 같은 snapshot 에 다시 수행해도 ranking 행·순위·pointer 가 바뀌지 않는다. 완료한 JobInstance 는 다시 실행되지 않는다.")
    @Test
    void republishIsNoop() throws Exception {
        db.activity(2701, 1, 4, "2026-07-20T01:00:00Z");
        db.activity(2701, 2, 4, "2026-07-20T01:00:00Z");
        db.activity(2702, 3, 9, "2026-07-21T01:00:00Z");
        WeeklyRankingTarget target = target("2026-07-20", 1);
        JobExecution execution = launch(target);
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        List<List<Long>> ranking = db.ranking(target.snapshotId());
        Map<String, Object> snapshot = db.snapshot(target.snapshotId()).orElseThrow();
        Map<String, Object> publication = db.publication(target.periodStart()).orElseThrow();
        assertThat(ranking).containsExactly(List.of(3L, 9L, 1L), List.of(1L, 4L, 2L), List.of(2L, 4L, 3L));

        SnapshotStatus again = new TransactionTemplate(transactionManager).execute(status -> publisher.publish(target, 999L));

        assertThat(again).isEqualTo(SnapshotStatus.PUBLISHED);
        assertThat(db.ranking(target.snapshotId())).isEqualTo(ranking);
        assertThat(db.snapshot(target.snapshotId()).orElseThrow()).isEqualTo(snapshot);
        assertThat(db.publication(target.periodStart()).orElseThrow()).isEqualTo(publication);
        assertThatThrownBy(() -> launch(target)).isInstanceOf(JobInstanceAlreadyCompleteException.class);
    }

    @DisplayName("writer 가 같은 상품을 다시 써도 점수는 절대값으로 수렴한다 (score = total, 더하지 않는다).")
    @Test
    void writerUpsertsAbsoluteScore() {
        String snapshotId = "writer-upsert-test";
        ScoreStageWriter writer = new ScoreStageWriter(store, snapshotId);

        writer.write(Chunk.of(new ProductScore(1, 5), new ProductScore(2, 3)));
        writer.write(Chunk.of(new ProductScore(1, 5), new ProductScore(2, 3)));
        writer.write(Chunk.of(new ProductScore(1, 5)));

        assertThat(db.stage(snapshotId)).containsExactly(List.of(1L, 5L), List.of(2L, 3L));
    }

    @DisplayName("주 시작 날짜와 periodEnd 는 UTC 기준이다: snapshotId·기간 끝이 parameter 로 정해진다.")
    @Test
    void derivesSnapshotFromParameters() {
        WeeklyRankingTarget target = WeeklyRankingTarget.of("2026-08-17", "2");

        assertThat(target.snapshotId()).isEqualTo("weekly:2026-08-17:r2");
        assertThat(target.periodEnd()).isEqualTo(LocalDate.parse("2026-08-24"));
        assertThat(target.endInstant()).hasToString("2026-08-24T00:00:00Z");
    }
}
