package com.loopers.job.ranking;

import com.loopers.batch.job.ranking.ProductScore;
import com.loopers.batch.job.ranking.RankingChecksum;
import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.ranking.WeeklyRankingTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * §6 작은 입력으로 재시작 확인. 2026-08-17 주의 r1 은 한 번에 끝나는 정상 실행(비교 기준, 별도 snapshot), r2 는 같은 입력을 다시 고정한 재시작 대상이다.
 * r2 는 세 번째 item(상품 9)의 chunk 를 commit 하기 전에 한 번 실패하고(writer 에서 주입), 같은 식별 parameter 로 다시 실행하면
 * 입력 고정·상품 7·8 을 다시 하지 않고 9 부터 이어 간다.
 * 실행: ./gradlew :apps:commerce-batch:test --tests '*RankingJobRestartTest' (Docker 필요).
 */
class RankingJobRestartTest extends WeeklyRankingJobTestSupport {
    private static final Logger log = LoggerFactory.getLogger(RankingJobRestartTest.class);
    private static final LocalDate WEEK = LocalDate.parse("2026-08-17");
    private static final String READ_COUNT = WeeklyRankingJobConfig.READER_NAME + ".read.count";

    /** 필드 TAB·행 LF·마지막 LF, orderId·productId 순, UTC 마이크로초 (설계 10-4). 운영 직렬화와 따로 손으로 적은 기대값. */
    private static final String EXPECTED_INPUT_TEXT = """
        42\t7\t2\t2026-08-17T01:00:00.000000Z
        42\t8\t1\t2026-08-17T01:00:00.000000Z
        43\t7\t3\t2026-08-18T01:00:00.000000Z
        44\t9\t5\t2026-08-19T01:00:00.000000Z
        """;
    private static final String EXPECTED_RESULT_TEXT = """
        7\t5\t1
        9\t5\t2
        8\t1\t3
        """;

    @DisplayName("[§6] r2 가 상품 9 의 chunk commit 전에 실패하면 FAILED·같은 JobInstance·read.count=2·stage 7,8 이고 공개본은 r1 그대로다. 같은 parameter 로 재실행하면 9 부터 이어 완료하고 r1 과 같은 점수·순위·checksum 이 된다.")
    @Test
    void restartsFromLastCheckpoint_andMatchesNormalRun() throws Exception {
        db.section6Fixture();
        WeeklyRankingTarget r1 = new WeeklyRankingTarget(WEEK, 1);
        WeeklyRankingTarget r2 = new WeeklyRankingTarget(WEEK, 2);

        // 0. 비교 기준: 같은 입력의 정상 한 번 실행 (r1)
        JobExecution normal = launch(r1);
        assertThat(normal.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Map<String, Object> normalSnapshot = db.snapshot(r1.snapshotId()).orElseThrow();
        assertThat(db.ranking(r1.snapshotId())).containsExactly(List.of(7L, 5L, 1L), List.of(9L, 5L, 2L), List.of(8L, 1L, 3L));
        assertThat(normalSnapshot.get("input_checksum")).isEqualTo(RankingChecksum.sha256(EXPECTED_INPUT_TEXT));
        assertThat(normalSnapshot.get("result_checksum")).isEqualTo(RankingChecksum.sha256(EXPECTED_RESULT_TEXT));
        assertThat(db.publishedSnapshotId(WEEK)).contains(r1.snapshotId());

        // 1~3. r2: 입력 고정 → chunk(2) 로 7·8 commit → 세 번째 item(9) 의 chunk 를 쓰는 중 commit 전에 한 번 실패
        List<List<Long>> writes = new CopyOnWriteArrayList<>();
        AtomicBoolean failed = new AtomicBoolean();
        doAnswer(invocation -> {
            List<? extends ProductScore> scores = invocation.getArgument(1);
            List<Long> productIds = scores.stream().map(ProductScore::productId).toList();
            writes.add(productIds);
            if (productIds.contains(9L) && failed.compareAndSet(false, true)) {
                throw new IllegalStateException("주입된 실패: 상품 9 의 chunk commit 전");
            }
            return invocation.callRealMethod();
        }).when(store).upsertScores(eq(r2.snapshotId()), any());
        JobExecution first = launch(r2);

        // 4. FAILED, 영속 checkpoint, stage 2행, 공개본 유지
        StepExecution firstFreeze = persistedStep(first, WeeklyRankingJobConfig.FREEZE_STEP);
        StepExecution firstScore = persistedStep(first, WeeklyRankingJobConfig.SCORE_STEP);
        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(firstFreeze.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(firstScore.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(persistedStep(first, WeeklyRankingJobConfig.PUBLISH_STEP)).isNull();
        assertThat(firstScore.getExecutionContext().getInt(READ_COUNT)).isEqualTo(2);
        assertThat(firstScore.getCommitCount()).isEqualTo(1);
        assertThat(firstScore.getWriteCount()).isEqualTo(2);
        assertThat(db.stage(r2.snapshotId())).containsExactly(List.of(7L, 5L), List.of(8L, 1L));
        assertThat(db.inputs(r2.snapshotId())).hasSize(4);
        assertThat(db.snapshot(r2.snapshotId()).orElseThrow().get("status")).isEqualTo("FROZEN");
        assertThat(db.ranking(r2.snapshotId())).isEmpty();
        assertThat(db.publishedSnapshotId(WEEK)).as("계산 실패 중에도 기존 공개본 유지").contains(r1.snapshotId());

        // 5~6. 같은 식별 parameter·같은 metadata 로 새 JobExecution
        JobExecution second = launch(r2);

        StepExecution secondScore = persistedStep(second, WeeklyRankingJobConfig.SCORE_STEP);
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getJobInstance().getInstanceId()).isEqualTo(first.getJobInstance().getInstanceId());
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(second.getStepExecutions()).extracting(StepExecution::getStepName)
            .as("완료한 입력 고정 step 은 다시 실행하지 않는다")
            .containsExactly(WeeklyRankingJobConfig.SCORE_STEP, WeeklyRankingJobConfig.PUBLISH_STEP);
        assertThat(secondScore.getReadCount()).isEqualTo(1);
        assertThat(secondScore.getWriteCount()).isEqualTo(1);
        // 완료 뒤 read.count 는 item 3 + 끝을 확인한 마지막 read(null) 1 = 4 (AbstractItemCountingItemStreamItemReader 는 null read 도 센다)
        assertThat(secondScore.getExecutionContext().getInt(READ_COUNT)).isEqualTo(4);
        verify(store, times(1)).copyInputs(eq(r2.snapshotId()), any(), any());
        assertThat(writes).as("7·8 은 다시 쓰지 않고 9 만 (실패한 시도 + 재시작)").containsExactly(List.of(7L, 8L), List.of(9L), List.of(9L));
        assertThat(db.inputs(r2.snapshotId())).as("완성된 입력을 다시 복사하지 않는다").hasSize(4);

        // 7. 정상 실행(r1)과 비교: 점수·순위·checksum, 공개 pointer 는 r2
        Map<String, Object> restartedSnapshot = db.snapshot(r2.snapshotId()).orElseThrow();
        assertThat(db.ranking(r2.snapshotId())).containsExactly(List.of(7L, 5L, 1L), List.of(9L, 5L, 2L), List.of(8L, 1L, 3L));
        assertThat(db.ranking(r2.snapshotId())).isEqualTo(db.ranking(r1.snapshotId()));
        assertThat(restartedSnapshot.get("input_checksum")).isEqualTo(normalSnapshot.get("input_checksum"));
        assertThat(restartedSnapshot.get("result_checksum")).isEqualTo(normalSnapshot.get("result_checksum"));
        assertThat(restartedSnapshot.get("status")).isEqualTo("PUBLISHED");
        assertThat(((Number) restartedSnapshot.get("input_count")).intValue()).isEqualTo(4);
        assertThat(((Number) restartedSnapshot.get("product_count")).intValue()).isEqualTo(3);
        assertThat(((Number) restartedSnapshot.get("frozen_by_execution_id")).longValue()).isEqualTo(first.getId());
        assertThat(((Number) restartedSnapshot.get("published_by_execution_id")).longValue()).isEqualTo(second.getId());
        assertThat(db.publishedSnapshotId(WEEK)).contains(r2.snapshotId());

        log.info("[W9 관찰: 재시작] jobInstance={}, executions={}→{}, 1차={} read.count={} commit={} stage={}, 2차={} read={} write={} steps={}, "
                + "input checksum={}, result checksum={}, ranking={}, r1 ranking={}, pointer={}",
            first.getJobInstance().getInstanceId(), first.getId(), second.getId(), first.getStatus(),
            firstScore.getExecutionContext().getInt(READ_COUNT), firstScore.getCommitCount(), List.of(List.of(7L, 5L), List.of(8L, 1L)),
            second.getStatus(), secondScore.getReadCount(), secondScore.getWriteCount(),
            second.getStepExecutions().stream().map(StepExecution::getStepName).toList(),
            restartedSnapshot.get("input_checksum"), restartedSnapshot.get("result_checksum"),
            db.ranking(r2.snapshotId()), db.ranking(r1.snapshotId()), db.publishedSnapshotId(WEEK).orElseThrow());
    }
}
