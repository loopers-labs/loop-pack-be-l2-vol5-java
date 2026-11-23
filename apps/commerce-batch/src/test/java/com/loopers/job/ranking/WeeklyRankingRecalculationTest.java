package com.loopers.job.ranking;

import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.ranking.domain.RankingPeriod;
import com.loopers.batch.job.ranking.domain.SnapshotConflictException;
import com.loopers.batch.job.ranking.domain.SnapshotId;
import com.loopers.job.ranking.support.WeeklyRankingJobTestSupport;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.boot.autoconfigure.batch.JobLauncherApplicationRunner;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

class WeeklyRankingRecalculationTest extends WeeklyRankingJobTestSupport {

    @Test
    void recalculatesFromFrozenPurchasesEvenAfterALatePurchase() throws Exception {
        database.insertWeek20260817Fixture();
        launch(snapshot(1));
        database.insertActivity(45, 8, 10, Instant.parse("2026-08-20T01:00:00Z"));

        var runner = new JobLauncherApplicationRunner(jobLauncher, jobExplorer, jobRepository);
        runner.setJobs(List.of(job));
        runner.setJobName(job.getName());
        runner.run("periodStart=" + WEEK, "snapshotId=" + snapshot(2),
                "sourceSnapshotId=" + snapshot(1) + ",java.lang.String,false");

        assertThat(ranking(snapshot(2))).containsExactly(
                List.of(7L, 5L, 1L), List.of(9L, 5L, 2L), List.of(8L, 1L, 3L));
        assertThat(manifest(snapshot(2))).containsEntry("input_checksum", manifest(snapshot(1)).get("input_checksum"))
                .containsEntry("frozen_at", manifest(snapshot(1)).get("frozen_at"));
        assertThat(publishedSnapshot(WEEK)).contains(snapshot(2));
    }

    @Test
    void recalculatesScoresEvenWhenTheSourceFailedWithIncorrectCommittedScores() throws Exception {
        database.insertWeek20260817Fixture();
        doThrow(new IllegalStateException("publication failed")).doCallRealMethod()
                .when(publicationStore).publishIfNewer(any(SnapshotId.class), any());
        assertThat(launch(snapshot(1)).getStatus()).isEqualTo(BatchStatus.FAILED);
        jdbc.update("UPDATE ranking_score_stage SET score = 99 WHERE snapshot_id = ? AND product_id = 7", snapshot(1));

        var recalculated = jobLauncher.run(job, fromSnapshot(snapshot(2), snapshot(1)));

        assertThat(recalculated.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(stageScores(snapshot(2))).containsEntry(7L, 5L);
        assertThat(stageScores(snapshot(1))).containsEntry(7L, 99L);
    }

    @Test
    void corruptSourceRollsBackCopiedInputAndKeepsPreviousPublication() throws Exception {
        database.insertWeek20260817Fixture();
        launch(snapshot(1));
        jdbc.update("UPDATE ranking_snapshot_input SET quantity = 2 WHERE snapshot_id = ? AND product_id = 8",
                snapshot(1));

        var failed = jobLauncher.run(job, fromSnapshot(snapshot(2), snapshot(1)));

        assertThat(failed.getAllFailureExceptions()).anyMatch(SnapshotConflictException.class::isInstance);
        assertThat(inputCount(snapshot(2))).isZero();
        assertThat(snapshotStore.findManifest(SnapshotId.of(RankingPeriod.parse(WEEK), 2))).isEmpty();
        assertThat(publishedSnapshot(WEEK)).contains(snapshot(1));
    }

    @Test
    void missingSourceDoesNotFallBackToThePurchaseLedger() throws Exception {
        database.insertWeek20260817Fixture();
        var failed = jobLauncher.run(job, fromSnapshot(snapshot(2), snapshot(1)));
        assertThat(failed.getAllFailureExceptions()).anyMatch(SnapshotConflictException.class::isInstance);
        assertThat(publishedSnapshot(WEEK)).isEmpty();
    }

    @Test
    void rejectsSourcesFromAnotherWeekOrFromTheTargetOrLaterRevision() {
        for (String source : List.of("weekly-20260810-r1", snapshot(2), snapshot(3))) {
            assertThatThrownBy(() -> jobLauncher.run(job, fromSnapshot(snapshot(2), source)))
                    .isInstanceOf(JobParametersInvalidException.class);
        }
    }

    @Test
    void failedRecalculationCanOnlyRestartWithItsOriginalSource() throws Exception {
        database.insertWeek20260817Fixture();
        launch(snapshot(1));
        var selected = fromSnapshot(snapshot(3), snapshot(1));
        doThrow(new IllegalStateException("publication failed")).doCallRealMethod()
                .when(publicationStore).publishIfNewer(any(SnapshotId.class), any());
        var failed = jobLauncher.run(job, selected);
        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);

        assertThatThrownBy(() -> launch(snapshot(3))).isInstanceOf(JobParametersInvalidException.class);
        assertThatThrownBy(() -> jobLauncher.run(job, fromSnapshot(snapshot(3), snapshot(2))))
                .isInstanceOf(JobParametersInvalidException.class);
        var resumed = jobLauncher.run(job, selected);
        assertThat(resumed.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(resumed.getJobInstance().getId()).isEqualTo(failed.getJobInstance().getId());
        assertThat(executedSteps(resumed)).containsExactly(WeeklyRankingJobConfig.PUBLISH_STEP);
    }

    private JobParameters fromSnapshot(String target, String source) {
        return new JobParametersBuilder(params(WEEK, target))
                .addString("sourceSnapshotId", source, false).toJobParameters();
    }
}
